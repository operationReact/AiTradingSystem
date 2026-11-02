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

SECTOR_INDEX_HISTORY_CONTRACT_VERSION = "nifty-sector-history.v1"
SECTOR_INDEX_HISTORY_URL = "https://www.nseindia.com/reports-indices-historical-index-data"
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
SectorIndexCode = Literal[
    "NIFTY_AUTO",
    "NIFTY_BANK",
    "NIFTY_CAPITAL_GOODS",
    "NIFTY_CHEMICALS",
    "NIFTY_CONSUMER_DURABLES",
    "NIFTY_FINANCIAL_SERVICES",
    "NIFTY_FMCG",
    "NIFTY_HEALTHCARE",
    "NIFTY_IT",
    "NIFTY_MEDIA",
    "NIFTY_METAL",
    "NIFTY_OIL_AND_GAS",
    "NIFTY_PHARMA",
    "NIFTY_POWER",
    "NIFTY_PRIVATE_BANK",
    "NIFTY_PSU_BANK",
    "NIFTY_REALTY",
    "NIFTY_TELECOMMUNICATIONS",
]
SECTOR_INDEX_CODES = {
    "NIFTY_AUTO",
    "NIFTY_BANK",
    "NIFTY_CAPITAL_GOODS",
    "NIFTY_CHEMICALS",
    "NIFTY_CONSUMER_DURABLES",
    "NIFTY_FINANCIAL_SERVICES",
    "NIFTY_FMCG",
    "NIFTY_HEALTHCARE",
    "NIFTY_IT",
    "NIFTY_MEDIA",
    "NIFTY_METAL",
    "NIFTY_OIL_AND_GAS",
    "NIFTY_PHARMA",
    "NIFTY_POWER",
    "NIFTY_PRIVATE_BANK",
    "NIFTY_PSU_BANK",
    "NIFTY_REALTY",
    "NIFTY_TELECOMMUNICATIONS",
}


def _aware(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("timestamp must be timezone-aware")
    return value


class SectorIndexDailyRow(BaseModel):
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
            raise ValueError("sector-index values must be finite")
        return value

    @model_validator(mode="after")
    def validate_ohlc(self) -> SectorIndexDailyRow:
        if self.high < max(self.open, self.close, self.low):
            raise ValueError("sector-index high is inconsistent with OHLC")
        if self.low > min(self.open, self.close, self.high):
            raise ValueError("sector-index low is inconsistent with OHLC")
        return self

    @property
    def available_at(self) -> datetime:
        return datetime.combine(
            self.trading_date,
            CONSERVATIVE_DAILY_AVAILABLE_TIME,
            tzinfo=MARKET_ZONE,
        )


class SectorIndexHistory(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["nifty-sector-history.v1"] = SECTOR_INDEX_HISTORY_CONTRACT_VERSION
    index_code: SectorIndexCode
    source_url: Literal[
        "https://www.nseindia.com/reports-indices-historical-index-data"
    ] = SECTOR_INDEX_HISTORY_URL
    source_sha256: Hash64
    rows: list[SectorIndexDailyRow] = Field(min_length=1, max_length=10_000)
    history_fingerprint: Hash64
    status: Literal["RESEARCH_MARKET_CONTEXT_ONLY"] = "RESEARCH_MARKET_CONTEXT_ONLY"
    can_authorize_capital: Literal[False] = False

    @model_validator(mode="after")
    def validate_history(self) -> SectorIndexHistory:
        dates = [row.trading_date for row in self.rows]
        if dates != sorted(dates):
            raise ValueError("sector-index history must be chronologically ordered")
        if len(dates) != len(set(dates)):
            raise ValueError("sector-index history cannot contain duplicate trading dates")
        return self

    def context_observations(self) -> list[IndiaContextObservation]:
        observations: list[IndiaContextObservation] = []
        previous: SectorIndexDailyRow | None = None
        for row in self.rows:
            prefix = f"sector.{self.index_code}"
            metrics: dict[str, float] = {
                f"{prefix}.close": row.close,
                f"{prefix}.shares_traded": float(row.shares_traded),
                f"{prefix}.turnover_cr": row.turnover_cr,
            }
            if previous is not None:
                metrics[f"{prefix}.return_1d"] = row.close / previous.close - 1.0
            for context_key, value in sorted(metrics.items()):
                source_id = canonical_fingerprint(
                    {
                        "contractVersion": SECTOR_INDEX_HISTORY_CONTRACT_VERSION,
                        "indexCode": self.index_code,
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
                        source="NSE_INDIA_HISTORICAL_SECTOR_INDEX",
                        source_id=source_id,
                    )
                )
            previous = row
        return observations

    def causal_source_fingerprint(self, *, as_of: datetime) -> str:
        _aware(as_of)
        causal = [row for row in self.rows if row.available_at <= as_of]
        if not causal:
            raise ValueError("no sector-index rows were available by as_of")
        return canonical_fingerprint(
            {
                "contractVersion": SECTOR_INDEX_HISTORY_CONTRACT_VERSION,
                "indexCode": self.index_code,
                "sourceUrl": SECTOR_INDEX_HISTORY_URL,
                "rows": [row.model_dump(mode="json") for row in causal],
            }
        )


def _parse_date(value: str) -> date:
    for pattern in ("%d-%b-%Y", "%d-%m-%Y", "%Y-%m-%d"):
        try:
            return datetime.strptime(value.strip(), pattern).date()
        except ValueError:
            continue
    raise ValueError(f"unsupported sector-index date: {value}")


def _parse_float(value: str, field: str) -> float:
    try:
        parsed = float(value.replace(",", "").strip())
    except ValueError as exc:
        raise ValueError(f"invalid sector-index numeric field: {field}") from exc
    if not math.isfinite(parsed):
        raise ValueError(f"invalid sector-index numeric field: {field}")
    return parsed


def _parse_int(value: str, field: str) -> int:
    normalized = value.replace(",", "").strip()
    try:
        parsed = int(normalized)
    except ValueError as exc:
        raise ValueError(f"invalid sector-index integer field: {field}") from exc
    if parsed < 0:
        raise ValueError(f"invalid sector-index integer field: {field}")
    return parsed


def parse_sector_index_history(
    source_bytes: bytes,
    *,
    index_code: str,
) -> SectorIndexHistory:
    """Parse one explicitly whitelisted official NSE sector-index historical CSV."""

    normalized_code = index_code.strip().upper().replace(" ", "_")
    if normalized_code not in SECTOR_INDEX_CODES:
        raise ValueError("unsupported NSE sector-index code")
    if not source_bytes:
        raise ValueError("sector-index source is empty")
    if len(source_bytes) > 5_000_000:
        raise ValueError("sector-index source exceeds the 5 MB safety bound")
    try:
        text = source_bytes.decode("utf-8-sig")
    except UnicodeDecodeError as exc:
        raise ValueError("sector-index source must be UTF-8 CSV") from exc

    reader = csv.DictReader(io.StringIO(text, newline=""))
    if reader.fieldnames != EXPECTED_HEADERS:
        raise ValueError("unexpected sector-index CSV headers")

    rows: list[SectorIndexDailyRow] = []
    for raw in reader:
        if len(rows) >= 10_000:
            raise ValueError("sector-index source exceeds the 10000-row safety bound")
        values = {key: (raw.get(key) or "").strip() for key in EXPECTED_HEADERS}
        if not all(values.values()):
            raise ValueError("sector-index row contains a blank required field")
        rows.append(
            SectorIndexDailyRow(
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
        raise ValueError("sector-index source contains no data rows")
    ordered = sorted(rows, key=lambda row: row.trading_date)
    source_sha256 = hashlib.sha256(source_bytes).hexdigest()
    payload = {
        "contractVersion": SECTOR_INDEX_HISTORY_CONTRACT_VERSION,
        "indexCode": normalized_code,
        "sourceUrl": SECTOR_INDEX_HISTORY_URL,
        "sourceSha256": source_sha256,
        "rows": [row.model_dump(mode="json") for row in ordered],
        "status": "RESEARCH_MARKET_CONTEXT_ONLY",
        "canAuthorizeCapital": False,
    }
    return SectorIndexHistory(
        index_code=normalized_code,
        source_sha256=source_sha256,
        rows=ordered,
        history_fingerprint=canonical_fingerprint(payload),
    )
