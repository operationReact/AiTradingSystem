from __future__ import annotations

import csv
import hashlib
import io
import math
from datetime import date, datetime, time
from typing import Annotated, Literal
from zoneinfo import ZoneInfo

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .fingerprints import canonical_fingerprint
from .india_context import IndiaContextObservation

NIFTY50_HISTORY_CONTRACT_VERSION = "nifty50-history.v1"
NIFTY50_HISTORY_URL = "https://www.nseindia.com/reports-indices-historical-index-data"
EXPECTED_HEADERS = [
    "Date",
    "Open",
    "High",
    "Low",
    "Close",
    "Shares Traded",
    "Turnover (₹ Cr)",
]
MARKET_ZONE = ZoneInfo("Asia/Kolkata")
CONSERVATIVE_DAILY_AVAILABLE_TIME = time(16, 0)
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]


def _aware(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("timestamp must be timezone-aware")
    return value


class Nifty50DailyRow(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    trading_date: date
    open: Annotated[float, Field(gt=0)]
    high: Annotated[float, Field(gt=0)]
    low: Annotated[float, Field(gt=0)]
    close: Annotated[float, Field(gt=0)]
    shares_traded: Annotated[int, Field(ge=0)]
    turnover_cr: Annotated[float, Field(ge=0)]

    @field_validator("open", "high", "low", "close", "turnover_cr")
    @classmethod
    def values_must_be_finite(cls, value: float) -> float:
        if not math.isfinite(value):
            raise ValueError("NIFTY 50 values must be finite")
        return value

    @model_validator(mode="after")
    def validate_ohlc(self) -> Nifty50DailyRow:
        if self.high < max(self.open, self.close, self.low):
            raise ValueError("NIFTY 50 high is inconsistent with OHLC")
        if self.low > min(self.open, self.close, self.high):
            raise ValueError("NIFTY 50 low is inconsistent with OHLC")
        return self

    @property
    def available_at(self) -> datetime:
        return datetime.combine(
            self.trading_date,
            CONSERVATIVE_DAILY_AVAILABLE_TIME,
            tzinfo=MARKET_ZONE,
        )


class Nifty50History(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["nifty50-history.v1"] = NIFTY50_HISTORY_CONTRACT_VERSION
    source_url: Literal[
        "https://www.nseindia.com/reports-indices-historical-index-data"
    ] = NIFTY50_HISTORY_URL
    source_sha256: Hash64
    rows: list[Nifty50DailyRow] = Field(min_length=1, max_length=10_000)
    history_fingerprint: Hash64
    status: Literal["RESEARCH_MARKET_CONTEXT_ONLY"] = "RESEARCH_MARKET_CONTEXT_ONLY"
    can_authorize_capital: Literal[False] = False

    @model_validator(mode="after")
    def validate_history(self) -> Nifty50History:
        dates = [row.trading_date for row in self.rows]
        if dates != sorted(dates):
            raise ValueError("NIFTY 50 history must be chronologically ordered")
        if len(dates) != len(set(dates)):
            raise ValueError("NIFTY 50 history cannot contain duplicate trading dates")
        return self

    def context_observations(self) -> list[IndiaContextObservation]:
        observations: list[IndiaContextObservation] = []
        previous: Nifty50DailyRow | None = None
        for row in self.rows:
            metrics: dict[str, float] = {
                "benchmark.NIFTY50.close": row.close,
                "benchmark.NIFTY50.shares_traded": float(row.shares_traded),
                "benchmark.NIFTY50.turnover_cr": row.turnover_cr,
            }
            if previous is not None:
                metrics["benchmark.NIFTY50.return_1d"] = row.close / previous.close - 1.0
            for context_key, value in sorted(metrics.items()):
                source_id = canonical_fingerprint(
                    {
                        "contractVersion": NIFTY50_HISTORY_CONTRACT_VERSION,
                        "tradingDate": row.trading_date.isoformat(),
                        "contextKey": context_key,
                        "value": value,
                    }
                )
                observations.append(
                    IndiaContextObservation(
                        context_key=context_key,
                        event_at=row.available_at,
                        available_at=row.available_at,
                        value=value,
                        source="NSE_INDIA_HISTORICAL_INDEX",
                        source_id=source_id,
                    )
                )
            previous = row
        return observations

    def causal_source_fingerprint(self, *, as_of: datetime) -> str:
        """Fingerprint only NIFTY 50 rows that were available by the decision time."""

        _aware(as_of)
        causal = [row for row in self.rows if row.available_at <= as_of]
        if not causal:
            raise ValueError("no NIFTY 50 rows were available by as_of")
        return canonical_fingerprint(
            {
                "contractVersion": NIFTY50_HISTORY_CONTRACT_VERSION,
                "sourceUrl": NIFTY50_HISTORY_URL,
                "rows": [row.model_dump(mode="json") for row in causal],
            }
        )


def _parse_date(value: str) -> date:
    for pattern in ("%d-%b-%Y", "%d-%m-%Y", "%Y-%m-%d"):
        try:
            return datetime.strptime(value.strip(), pattern).date()
        except ValueError:
            continue
    raise ValueError(f"unsupported NIFTY 50 date: {value}")


def _parse_float(value: str, field: str) -> float:
    try:
        parsed = float(value.replace(",", "").strip())
    except ValueError as exc:
        raise ValueError(f"invalid NIFTY 50 numeric field: {field}") from exc
    if not math.isfinite(parsed):
        raise ValueError(f"invalid NIFTY 50 numeric field: {field}")
    return parsed


def _parse_int(value: str, field: str) -> int:
    normalized = value.replace(",", "").strip()
    try:
        parsed = int(normalized)
    except ValueError as exc:
        raise ValueError(f"invalid NIFTY 50 integer field: {field}") from exc
    if parsed < 0:
        raise ValueError(f"invalid NIFTY 50 integer field: {field}")
    return parsed


def parse_nifty50_history(source_bytes: bytes) -> Nifty50History:
    """Parse bounded official NSE NIFTY 50 historical CSV evidence."""

    if not source_bytes:
        raise ValueError("NIFTY 50 source is empty")
    if len(source_bytes) > 5_000_000:
        raise ValueError("NIFTY 50 source exceeds the 5 MB safety bound")
    try:
        text = source_bytes.decode("utf-8-sig")
    except UnicodeDecodeError as exc:
        raise ValueError("NIFTY 50 source must be UTF-8 CSV") from exc

    reader = csv.DictReader(io.StringIO(text, newline=""))
    if reader.fieldnames != EXPECTED_HEADERS:
        raise ValueError("unexpected NIFTY 50 CSV headers")

    rows: list[Nifty50DailyRow] = []
    for raw in reader:
        if len(rows) >= 10_000:
            raise ValueError("NIFTY 50 source exceeds the 10000-row safety bound")
        values = {key: (raw.get(key) or "").strip() for key in EXPECTED_HEADERS}
        if not all(values.values()):
            raise ValueError("NIFTY 50 row contains a blank required field")
        rows.append(
            Nifty50DailyRow(
                trading_date=_parse_date(values["Date"]),
                open=_parse_float(values["Open"], "Open"),
                high=_parse_float(values["High"], "High"),
                low=_parse_float(values["Low"], "Low"),
                close=_parse_float(values["Close"], "Close"),
                shares_traded=_parse_int(values["Shares Traded"], "Shares Traded"),
                turnover_cr=_parse_float(values["Turnover (₹ Cr)"], "Turnover (₹ Cr)"),
            )
        )

    if not rows:
        raise ValueError("NIFTY 50 source contains no data rows")
    ordered = sorted(rows, key=lambda row: row.trading_date)
    source_sha256 = hashlib.sha256(source_bytes).hexdigest()
    payload = {
        "contractVersion": NIFTY50_HISTORY_CONTRACT_VERSION,
        "sourceUrl": NIFTY50_HISTORY_URL,
        "sourceSha256": source_sha256,
        "rows": [row.model_dump(mode="json") for row in ordered],
        "status": "RESEARCH_MARKET_CONTEXT_ONLY",
        "canAuthorizeCapital": False,
    }
    return Nifty50History(
        source_sha256=source_sha256,
        rows=ordered,
        history_fingerprint=canonical_fingerprint(payload),
    )
