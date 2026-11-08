"""Fail-closed cash-equity tick integrity core for Angel One SmartWebSocketV2.

This module deliberately does not open a socket, retrieve credentials, or publish
customer data. A separately approved, long-lived private collector must own
authentication, network reconnects, symbol entitlements and authorized fan-out.

Protocol: Angel One SmartWebSocketV2, LTP mode 1; little-endian binary fields.
Only NSE_CM (exchangeType 1) and BSE_CM (exchangeType 3) are accepted.
"""
from __future__ import annotations

import re
import secrets
import struct
from dataclasses import dataclass
from datetime import datetime, timezone, timedelta
from decimal import Decimal
from threading import RLock
from typing import Iterable

LTP_MODE = 1
NSE_CM = 1
BSE_CM = 3
_EXCHANGES = {NSE_CM: "NSE", BSE_CM: "BSE"}
_LTP_PACKET_BYTES = 51
_MIN_EXCHANGE_TIME_MS = 1577836800000  # 2020-01-01T00:00:00Z
_PAISE = Decimal(100)


class TickRejected(ValueError):
    """A generic nonsecret failure; do not log a provider payload or token."""


class FeedUnavailable(RuntimeError):
    """A quote may not be used for a trading decision."""


@dataclass(frozen=True)
class InstrumentRef:
    """Verified active catalogue identity, not user-supplied subscription data."""

    instrument_id: int
    exchange_type: int
    token: str

    def __post_init__(self) -> None:
        if (isinstance(self.instrument_id, bool) or self.instrument_id <= 0
                or self.exchange_type not in _EXCHANGES
                or not isinstance(self.token, str)
                or re.fullmatch(r"[0-9]{1,25}", self.token) is None):
            raise ValueError("Invalid active cash instrument identity")

    @property
    def key(self) -> tuple[int, str]:
        return self.exchange_type, self.token


@dataclass(frozen=True)
class PriceTick:
    instrument_id: int
    exchange: str
    price: Decimal
    sequence: int
    exchange_at: datetime
    received_at: datetime
    connection_epoch: int


@dataclass(frozen=True)
class QuoteSnapshot:
    """A stale price is retained for display only; status is authoritative."""

    status: str  # FRESH, STALE, UNAVAILABLE, FEED_STALLED, DISCONNECTED
    tick: PriceTick | None
    age_ms: int | None


def decode_ltp_packet(packet: bytes, received_at: datetime) -> tuple[int, str, int, datetime, Decimal]:
    """Validate Angel One's 51-byte LTP frame without trusting unbounded input.

    The provider encodes its Indian cash-equity LTP as an integer in paise and
    exchange timestamp as epoch milliseconds. The collector must still verify
    quote correctness against the official broker during the live pilot.
    """
    if (not isinstance(packet, bytes) or len(packet) != _LTP_PACKET_BYTES
            or received_at.tzinfo is None):
        raise TickRejected("Invalid binary market tick")
    mode, exchange, raw_token, seq, exchange_ms, price_paise = struct.unpack(
        "<BB25sqqq", packet
    )
    if mode != LTP_MODE or exchange not in _EXCHANGES or seq < 0 or price_paise <= 0:
        raise TickRejected("Unsupported or invalid market tick")
    raw_value = raw_token.split(b"\x00", 1)[0]
    if (not raw_value or not raw_value.isascii() or
            re.fullmatch(rb"[0-9]{1,25}", raw_value) is None or
            raw_token != raw_value.ljust(25, b"\x00")):
        raise TickRejected("Invalid instrument token")
    received_ms = int(received_at.timestamp() * 1000)
    if (exchange_ms < _MIN_EXCHANGE_TIME_MS
            or exchange_ms > received_ms + 5000):
        raise TickRejected("Invalid exchange timestamp")
    exchange_at = datetime.fromtimestamp(exchange_ms / 1000, timezone.utc)
    return exchange, raw_value.decode("ascii"), seq, exchange_at, Decimal(price_paise) / _PAISE


def subscription_requests(instruments: Iterable[InstrumentRef], batch_size: int = 50) -> list[dict]:
    """Create bounded LTP-only subscription requests from catalogue-approved keys.

    This is not a proof of broker entitlement or an automatic subscription to
    all 2,778 catalogue entries. The caller must apply its broker/token budget.
    """
    if not 1 <= batch_size <= 100:
        raise ValueError("Unsafe subscription batch size")
    grouped: dict[int, set[str]] = {NSE_CM: set(), BSE_CM: set()}
    seen: set[tuple[int, str]] = set()
    for ref in instruments:
        if not isinstance(ref, InstrumentRef) or ref.key in seen:
            raise ValueError("Duplicate or invalid cash instrument")
        seen.add(ref.key)
        if len(seen) > 1000:
            raise ValueError("Subscription budget exceeded")
        grouped[ref.exchange_type].add(ref.token)
    requests: list[dict] = []
    for exchange_type, tokens in grouped.items():
        ordered = sorted(tokens, key=lambda t: (int(t), t))
        for start in range(0, len(ordered), batch_size):
            requests.append({
                "correlationID": secrets.token_hex(5),  # 10 alphanumeric chars
                "action": 1,
                "params": {"mode": LTP_MODE, "tokenList": [
                    {"exchangeType": exchange_type,
                     "tokens": ordered[start:start + batch_size]}
                ]},
            })
    return requests


class TickCache:
    """Bounded, thread-safe, connection-epoch-aware latest-tick cache.

    Monotonic receive time and exchange timestamp are separate clocks. A
    healthy socket/heartbeat never makes an old security price fresh.
    Disconnections invalidate all prior prices for trading eligibility.
    """

    def __init__(self, instruments: Iterable[InstrumentRef], *,
                 max_watch: int = 256, max_tick_age_ms: int = 3000,
                 heartbeat_timeout_ms: int = 20000) -> None:
        if (not 1 <= max_watch <= 1000 or not 100 <= max_tick_age_ms <= 60000
                or not 1000 <= heartbeat_timeout_ms <= 120000):
            raise ValueError("Unsafe tick integrity limits")
        refs = list(instruments)
        if not refs or len(refs) > max_watch or len({x.key for x in refs}) != len(refs):
            raise ValueError("Invalid bounded active-instrument watchlist")
        self._instruments = {x.key: x for x in refs}
        self._max_tick_age_ms = max_tick_age_ms
        self._heartbeat_timeout_ms = heartbeat_timeout_ms
        self._lock = RLock()
        self._connected = False
        self._last_heartbeat_ms: int | None = None
        self._epoch = 0
        self._ticks: dict[tuple[int, str], tuple[PriceTick, int]] = {}

    def connected(self, monotonic_ms: int) -> None:
        with self._lock:
            self._epoch += 1
            self._ticks.clear()  # never reuse stale prices after reconnect
            self._connected = True
            self._last_heartbeat_ms = monotonic_ms

    def heartbeat(self, monotonic_ms: int) -> None:
        with self._lock:
            if self._connected:
                self._last_heartbeat_ms = monotonic_ms

    def disconnected(self) -> None:
        with self._lock:
            self._connected = False
            self._last_heartbeat_ms = None
            self._ticks.clear()

    def ingest(self, packet: bytes, received_at: datetime,
               monotonic_ms: int) -> PriceTick | None:
        exchange, token, sequence, exchange_at, price = decode_ltp_packet(packet, received_at)
        key = (exchange, token)
        with self._lock:
            if not self._connected or key not in self._instruments:
                return None
            previous = self._ticks.get(key)
            if previous is not None and (
                    sequence <= previous[0].sequence or exchange_at < previous[0].exchange_at):
                return None  # duplicated/reordered frames cannot roll back price
            ref = self._instruments[key]
            tick = PriceTick(ref.instrument_id, _EXCHANGES[exchange], price, sequence,
                             exchange_at, received_at, self._epoch)
            self._ticks[key] = (tick, monotonic_ms)
            return tick

    def snapshot(self, ref: InstrumentRef, received_at: datetime,
                 monotonic_ms: int) -> QuoteSnapshot:
        if received_at.tzinfo is None:
            raise ValueError("UTC-aware receive clock required")
        with self._lock:
            if ref.key not in self._instruments:
                raise ValueError("Unsubscribed instrument")
            if not self._connected:
                return QuoteSnapshot("DISCONNECTED", None, None)
            stalled = (self._last_heartbeat_ms is None or
                       monotonic_ms - self._last_heartbeat_ms > self._heartbeat_timeout_ms)
            stored = self._ticks.get(ref.key)
            if stored is None:
                return QuoteSnapshot("FEED_STALLED" if stalled else "UNAVAILABLE", None, None)
            tick, arrival_mono = stored
            receipt_age = max(0, monotonic_ms - arrival_mono)
            exchange_age = max(0, int((received_at - tick.exchange_at).total_seconds() * 1000))
            age = max(receipt_age, exchange_age)
            status = ("FEED_STALLED" if stalled else "STALE"
                      if age > self._max_tick_age_ms else "FRESH")
            return QuoteSnapshot(status, tick, age)

    def require_fresh(self, ref: InstrumentRef, received_at: datetime,
                      monotonic_ms: int) -> PriceTick:
        snap = self.snapshot(ref, received_at, monotonic_ms)
        if snap.status != "FRESH" or snap.tick is None:
            raise FeedUnavailable("Verified fresh market price unavailable")
        return snap.tick


def reconnect_delay_seconds(attempt: int, entropy: float = 0.5) -> float:
    """Bounded exponential backoff with jitter; must reset after stable recovery."""
    if attempt < 0 or not 0 <= entropy <= 1:
        raise ValueError("Invalid reconnect policy")
    base = min(60.0, float(2 ** min(attempt, 6)))
    return min(90.0, base * (0.75 + entropy * 0.5))
