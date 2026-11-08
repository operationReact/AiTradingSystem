"""Pure, fail-closed Angel One SmartAPI WebSocket V2 LTP tick boundary.

This module makes no network or broker calls and has no order authority.
A long-lived, authenticated streaming worker must own the socket, call these
methods, and forward verified values only over a separately authorized channel.
Broker tokens stay internal; downstream consumers use canonical instrument IDs.
"""

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from decimal import Decimal
import re
import struct
from typing import Mapping


NSE_CASH = 1
BSE_CASH = 3
LTP_MODE = 1
MAX_SUBSCRIPTIONS = 1000
PRICE_SCALE = Decimal("100")


class RejectedTick(ValueError):
    """Fixed error code only; never embeds raw frames, symbols or credentials."""

    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


@dataclass(frozen=True)
class VerifiedLiveTick:
    instrument_id: int
    exchange: str
    ltp: Decimal
    exchange_at: datetime
    received_at: datetime
    sequence: int
    source: str = "ANGEL_ONE_SMARTAPI_WS_V2"


class LiveTickIntegrityGate:
    """Session-scoped identity, tick freshness and monotonicity verification.

    The allowed map is resolved server-side from the active instrument catalogue,
    keyed by (provider exchange type, provider token), valued by canonical ID.
    This gate never trusts arbitrary client-provided token/instrument mappings.
    """

    def __init__(
        self,
        allowed: Mapping[tuple[int, str], int],
        *,
        max_tick_age: timedelta = timedelta(seconds=10),
        max_future_skew: timedelta = timedelta(seconds=2),
        heartbeat_timeout: timedelta = timedelta(seconds=45),
    ):
        if (not allowed or len(allowed) > MAX_SUBSCRIPTIONS
                or max_tick_age <= timedelta(0)
                or max_future_skew < timedelta(0)
                or heartbeat_timeout <= timedelta(0)):
            raise ValueError("INVALID_FEED_CONFIGURATION")
        for (exchange, token), instrument_id in allowed.items():
            if (exchange not in (NSE_CASH, BSE_CASH)
                    or not isinstance(token, str)
                    or not re.fullmatch(r"[0-9]{1,25}", token)
                    or type(instrument_id) is not int or instrument_id <= 0):
                raise ValueError("INVALID_FEED_CONFIGURATION")
        if len(set(allowed.values())) != len(allowed):
            raise ValueError("DUPLICATE_INSTRUMENT_ID")
        self._allowed = dict(allowed)
        self._max_tick_age = max_tick_age
        self._max_future_skew = max_future_skew
        self._heartbeat_timeout = heartbeat_timeout
        self._connected_at: datetime | None = None
        self._last_pong_at: datetime | None = None
        self._latest: dict[int, VerifiedLiveTick] = {}
        self._last_sequence: dict[tuple[int, str], int] = {}
        self._last_exchange_time: dict[tuple[int, str], datetime] = {}

    def subscription_request(self, correlation_id: str) -> dict:
        """Read-only LTP subscriptions. Enforced quota before socket send."""
        if not isinstance(correlation_id, str) or not re.fullmatch(r"[A-Za-z0-9]{1,10}", correlation_id):
            raise ValueError("INVALID_CORRELATION_ID")
        token_list = [
            {"exchangeType": exchange, "tokens": sorted(
                (token for venue, token in self._allowed if venue == exchange),
                key=lambda s: (len(s), s),
            )}
            for exchange in (NSE_CASH, BSE_CASH)
            if any(venue == exchange for venue, _ in self._allowed)
        ]
        return {"correlationID": correlation_id, "action": 1,
                "params": {"mode": LTP_MODE, "tokenList": token_list}}

    @staticmethod
    def _require_aware(moment: datetime) -> datetime:
        if not isinstance(moment, datetime) or moment.tzinfo is None or moment.utcoffset() is None:
            raise ValueError("UTC_CLOCK_REQUIRED")
        return moment.astimezone(timezone.utc)

    def mark_connected(self, *, at: datetime) -> None:
        self._connected_at = self._require_aware(at)
        self._last_pong_at = None
        self._latest.clear()
        self._last_sequence.clear()
        self._last_exchange_time.clear()

    def mark_disconnected(self) -> None:
        self._connected_at = None
        self._last_pong_at = None
        self._latest.clear()
        self._last_sequence.clear()
        self._last_exchange_time.clear()

    def mark_pong(self, *, at: datetime) -> None:
        moment = self._require_aware(at)
        if self._connected_at is not None and moment >= self._connected_at:
            self._last_pong_at = moment
        # A heartbeat is not a market tick and cannot refresh a quote.

    def _connection_healthy(self, now: datetime) -> bool:
        last = self._last_pong_at or self._connected_at
        return last is not None and timedelta(0) <= now - last <= self._heartbeat_timeout

    def accept_ltp(self, payload: bytes, *, received_at: datetime) -> VerifiedLiveTick:
        now = self._require_aware(received_at)
        if not self._connection_healthy(now):
            raise RejectedTick("FEED_DISCONNECTED")
        if not isinstance(payload, bytes) or len(payload) != 51:
            raise RejectedTick("INVALID_TICK_LENGTH")
        mode, exchange = payload[0], payload[1]
        if mode != LTP_MODE or exchange not in (NSE_CASH, BSE_CASH):
            raise RejectedTick("UNSUPPORTED_TICK_MODE_OR_EXCHANGE")
        try:
            token = payload[2:27].split(b"\0", 1)[0].decode("ascii")
        except UnicodeDecodeError:
            raise RejectedTick("INVALID_TICK_TOKEN") from None
        if not re.fullmatch(r"[0-9]{1,25}", token) or (exchange, token) not in self._allowed:
            raise RejectedTick("UNSUBSCRIBED_INSTRUMENT")
        sequence, timestamp_ms, price_in_paise = struct.unpack_from("<qqq", payload, 27)
        if sequence < 0 or timestamp_ms <= 0 or price_in_paise <= 0:
            raise RejectedTick("INVALID_TICK_VALUES")
        try:
            exchange_at = datetime.fromtimestamp(timestamp_ms / 1000, tz=timezone.utc)
        except (OverflowError, OSError, ValueError):
            raise RejectedTick("INVALID_TICK_TIMESTAMP") from None
        if exchange_at > now + self._max_future_skew:
            raise RejectedTick("FUTURE_TICK")
        if now - exchange_at > self._max_tick_age:
            raise RejectedTick("STALE_TICK")
        key = (exchange, token)
        if (sequence <= self._last_sequence.get(key, -1)
                or exchange_at < self._last_exchange_time.get(key, datetime.min.replace(tzinfo=timezone.utc))):
            raise RejectedTick("OUT_OF_ORDER_TICK")
        tick = VerifiedLiveTick(
            instrument_id=self._allowed[key],
            exchange="NSE" if exchange == NSE_CASH else "BSE",
            ltp=Decimal(price_in_paise) / PRICE_SCALE,
            exchange_at=exchange_at,
            received_at=now,
            sequence=sequence,
        )
        self._last_sequence[key] = sequence
        self._last_exchange_time[key] = exchange_at
        self._latest[tick.instrument_id] = tick
        return tick

    def live_quote(self, instrument_id: int, *, now: datetime, market_open: bool) -> VerifiedLiveTick | None:
        """No stale/closed/disconnected quote can be labelled LIVE."""
        moment = self._require_aware(now)
        if not market_open or not self._connection_healthy(moment):
            return None
        tick = self._latest.get(instrument_id)
        if tick is None or tick.exchange_at > moment + self._max_future_skew:
            return None
        if moment - tick.exchange_at > self._max_tick_age:
            return None
        return tick
