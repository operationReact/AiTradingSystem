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

INDIA_VIX_HISTORY_CONTRACT_VERSION = "india-vix-history.v1"
INDIA_VIX_HISTORY_URL = "https://www.nseindia.com/reports-indices-historical-vix"
EXPECTED_HEADERS = [
    "Date",
    "Open",
    "High",
    "Low",
    "Close",
    "Prev. Close",
    "Change",
    "% Change",
]
MARKET_ZONE = ZoneInfo("Asia/Kolkata")
CONSERVATIVE_DAILY_AVAILABLE_TIME = time(16, 0)
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]


class IndiaVixDailyRow(BaseModel):
    """One official NSE daily India VIX OHLC observation."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    trading_date: date
    open: Annotated[float, Field(gt=0)]
    high: Annotated[float, Field(gt=0)]
    low: Annotated[float, Field(gt=0)]
    close: Annotated[float, Field(gt=0)]
    previous_close: Annotated[float, Field(gt=0)]
    change: float
    percent_change: float

    @field_validator("open", "high", "low", "close", "previous_close", "change", "percent_change")
    @classmethod
    def values_must_be_finite(cls, value: float) -> float:
        if not math.isfinite(value):
            raise ValueError("India VIX values must be finite")
        return value

    @model_validator(mode="after")
    def validate_ohlc(self) -> IndiaVixDailyRow:
        if self.high < max(self.open, self.close, self.low):
            raise ValueError("India VIX high is inconsistent with OHLC")
        if self.low > min(self.open, self.close, self.high):
            raise ValueError("India VIX low is inconsistent with OHLC")
        if abs((self.close - self.previous_close) - self.change) > 0.02:
            raise ValueError("India VIX change is inconsistent with close and previous close")
        expected_percent = self.change / self.previous_close * 100.0
        if abs(expected_percent - self.percent_change) > 0.10:
            raise ValueError("India VIX percent change is inconsistent with published prices")
        return self

    @property
    def available_at(self) -> datetime:
        return datetime.combine(
            self.trading_date,
            CONSERVATIVE_DAILY_AVAILABLE_TIME,
            tzinfo=MARKET_ZONE,
        )

    def to_close_context_observation(self) -> IndiaContextObservation:
        source_id = canonical_fingerprint(
            {
                "contractVersion": INDIA_VIX_HISTORY_CONTRACT_VERSION,
                "tradingDate": self.trading_date.isoformat(),
                "close": self.close,
                "previousClose": self.previous_close,
                "change": self.change,
                "percentChange": self.percent_change,
            }
        )
        return IndiaContextObservation(
            context_key="volatility.INDIA_VIX.close",
            event_at=self.available_at,
            available_at=self.available_at,
            value=self.close,
            source="NSE_INDIA_HISTORICAL_VIX",
            source_id=source_id,
        )


class IndiaVixHistory(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["india-vix-history.v1"] = INDIA_VIX_HISTORY_CONTRACT_VERSION
    source_url: Literal["https://www.nseindia.com/reports-indices-historical-vix"] = (
        INDIA_VIX_HISTORY_URL
    )
    source_sha256: Hash64
    rows: list[IndiaVixDailyRow] = Field(min_length=1, max_length=10_000)
    history_fingerprint: Hash64
    status: Literal["RESEARCH_MARKET_CONTEXT_ONLY"] = "RESEARCH_MARKET_CONTEXT_ONLY"
    can_authorize_capital: Literal[False] = False

    @model_validator(mode="after")
    def validate_history(self) -> IndiaVixHistory:
        dates = [row.trading_date for row in self.rows]
        if dates != sorted(dates):
            raise ValueError("India VIX history must be chronologically ordered")
        if len(dates) != len(set(dates)):
            raise ValueError("India VIX history cannot contain duplicate trading dates")
        return self

    def close_observations(self) -> list[IndiaContextObservation]:
        return [row.to_close_context_observation() for row in self.rows]


def _parse_date(value: str) -> date:
    for pattern in ("%d-%b-%Y", "%d-%m-%Y", "%Y-%m-%d"):
        try:
            return datetime.strptime(value.strip(), pattern).date()
        except ValueError:
            continue
    raise ValueError(f"unsupported India VIX date: {value}")


def _parse_float(value: str, field: str) -> float:
    try:
        parsed = float(value.replace(",", "").strip())
    except ValueError as exc:
        raise ValueError(f"invalid India VIX numeric field: {field}") from exc
    if not math.isfinite(parsed):
        raise ValueError(f"invalid India VIX numeric field: {field}")
    return parsed


def parse_india_vix_history(source_bytes: bytes) -> IndiaVixHistory:
    """Parse bounded official NSE India VIX historical CSV evidence."""

    if not source_bytes:
        raise ValueError("India VIX source is empty")
    if len(source_bytes) > 5_000_000:
        raise ValueError("India VIX source exceeds the 5 MB safety bound")
    try:
        text = source_bytes.decode("utf-8-sig")
    except UnicodeDecodeError as exc:
        raise ValueError("India VIX source must be UTF-8 CSV") from exc

    reader = csv.DictReader(io.StringIO(text, newline=""))
    if reader.fieldnames != EXPECTED_HEADERS:
        raise ValueError("unexpected India VIX CSV headers")

    rows: list[IndiaVixDailyRow] = []
    for raw in reader:
        if len(rows) >= 10_000:
            raise ValueError("India VIX source exceeds the 10000-row safety bound")
        values = {key: (raw.get(key) or "").strip() for key in EXPECTED_HEADERS}
        if not all(values.values()):
            raise ValueError("India VIX row contains a blank required field")
        rows.append(
            IndiaVixDailyRow(
                trading_date=_parse_date(values["Date"]),
                open=_parse_float(values["Open"], "Open"),
                high=_parse_float(values["High"], "High"),
                low=_parse_float(values["Low"], "Low"),
                close=_parse_float(values["Close"], "Close"),
                previous_close=_parse_float(values["Prev. Close"], "Prev. Close"),
                change=_parse_float(values["Change"], "Change"),
                percent_change=_parse_float(values["% Change"], "% Change"),
            )
        )

    if not rows:
        raise ValueError("India VIX source contains no data rows")
    ordered = sorted(rows, key=lambda row: row.trading_date)
    source_sha256 = hashlib.sha256(source_bytes).hexdigest()
    payload = {
        "contractVersion": INDIA_VIX_HISTORY_CONTRACT_VERSION,
        "sourceUrl": INDIA_VIX_HISTORY_URL,
        "sourceSha256": source_sha256,
        "rows": [row.model_dump(mode="json") for row in ordered],
        "status": "RESEARCH_MARKET_CONTEXT_ONLY",
        "canAuthorizeCapital": False,
    }
    return IndiaVixHistory(
        source_sha256=source_sha256,
        rows=ordered,
        history_fingerprint=canonical_fingerprint(payload),
    )
