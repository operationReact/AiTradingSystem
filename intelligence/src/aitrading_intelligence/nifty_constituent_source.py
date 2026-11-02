from __future__ import annotations

import csv
import hashlib
import io
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .fingerprints import canonical_fingerprint

NIFTY_CONSTITUENT_CONTRACT_VERSION = "nifty-constituent-snapshot.v1"
NIFTY_50_CONSTITUENT_URL = (
    "https://www.niftyindices.com/IndexConstituent/ind_nifty50list.csv"
)
NIFTY_500_CONSTITUENT_URL = (
    "https://www.niftyindices.com/IndexConstituent/ind_nifty500list.csv"
)
EXPECTED_HEADERS = ["Company Name", "Industry", "Symbol", "Series", "ISIN Code"]
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
Symbol = Annotated[str, Field(pattern=r"^[A-Z0-9&._-]{1,32}$")]
Isin = Annotated[str, Field(pattern=r"^[A-Z]{2}[A-Z0-9]{9}[0-9]$")]
IndexCode = Literal["NIFTY50", "NIFTY500"]
_SOURCE_URLS: dict[IndexCode, str] = {
    "NIFTY50": NIFTY_50_CONSTITUENT_URL,
    "NIFTY500": NIFTY_500_CONSTITUENT_URL,
}


def _aware(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("timestamp must be timezone-aware")
    return value


class NiftyConstituent(BaseModel):
    """One row from an official NSE Indices constituent CSV."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    company_name: Annotated[str, Field(min_length=1, max_length=240)]
    industry_label: Annotated[str, Field(min_length=1, max_length=160)]
    symbol: Symbol
    series: Annotated[str, Field(min_length=1, max_length=24)]
    isin: Isin


class NiftyConstituentSnapshot(BaseModel):
    """First-observed point-in-time constituent evidence; never backdated."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["nifty-constituent-snapshot.v1"] = (
        NIFTY_CONSTITUENT_CONTRACT_VERSION
    )
    index_code: IndexCode
    source_url: Annotated[str, Field(min_length=1, max_length=512)]
    observed_at: datetime
    source_sha256: Hash64
    constituents: list[NiftyConstituent] = Field(min_length=1, max_length=500)
    snapshot_fingerprint: Hash64
    status: Literal["RESEARCH_INDEX_MEMBERSHIP_ONLY"] = "RESEARCH_INDEX_MEMBERSHIP_ONLY"
    can_authorize_capital: Literal[False] = False

    @field_validator("observed_at")
    @classmethod
    def observed_at_must_be_aware(cls, value: datetime) -> datetime:
        return _aware(value)

    @model_validator(mode="after")
    def validate_snapshot(self) -> NiftyConstituentSnapshot:
        if self.source_url != _SOURCE_URLS[self.index_code]:
            raise ValueError("source_url does not match the official URL for index_code")
        identities = [(row.symbol, row.series, row.isin) for row in self.constituents]
        if identities != sorted(identities):
            raise ValueError("constituents must use canonical symbol/series/ISIN ordering")
        symbols = [row.symbol for row in self.constituents]
        if len(symbols) != len(set(symbols)):
            raise ValueError("constituent symbols must be unique")
        isins = [row.isin for row in self.constituents]
        if len(isins) != len(set(isins)):
            raise ValueError("constituent ISIN values must be unique")
        return self


def _parse_constituents(
    source_bytes: bytes,
    *,
    observed_at: datetime,
    index_code: IndexCode,
) -> NiftyConstituentSnapshot:
    _aware(observed_at)
    if not source_bytes:
        raise ValueError("NIFTY constituent source is empty")
    if len(source_bytes) > 2_000_000:
        raise ValueError("NIFTY constituent source exceeds the 2 MB safety bound")
    try:
        text = source_bytes.decode("utf-8-sig")
    except UnicodeDecodeError as exc:
        raise ValueError("NIFTY constituent source must be UTF-8 CSV") from exc

    reader = csv.DictReader(io.StringIO(text, newline=""))
    if reader.fieldnames != EXPECTED_HEADERS:
        raise ValueError("unexpected NIFTY constituent CSV headers")

    parsed: list[NiftyConstituent] = []
    for raw in reader:
        if len(parsed) >= 500:
            raise ValueError("NIFTY constituent source exceeds the 500-row safety bound")
        values = {key: (raw.get(key) or "").strip() for key in EXPECTED_HEADERS}
        if not all(values.values()):
            raise ValueError("NIFTY constituent row contains a blank required field")
        parsed.append(
            NiftyConstituent(
                company_name=values["Company Name"],
                industry_label=values["Industry"],
                symbol=values["Symbol"].upper(),
                series=values["Series"].upper(),
                isin=values["ISIN Code"].replace(" ", "").upper(),
            )
        )

    if not parsed:
        raise ValueError("NIFTY constituent source contains no data rows")
    ordered = sorted(parsed, key=lambda row: (row.symbol, row.series, row.isin))
    source_sha256 = hashlib.sha256(source_bytes).hexdigest()
    source_url = _SOURCE_URLS[index_code]
    payload = {
        "contractVersion": NIFTY_CONSTITUENT_CONTRACT_VERSION,
        "indexCode": index_code,
        "sourceUrl": source_url,
        "observedAt": observed_at.isoformat(),
        "sourceSha256": source_sha256,
        "constituents": [row.model_dump(mode="json") for row in ordered],
        "status": "RESEARCH_INDEX_MEMBERSHIP_ONLY",
        "canAuthorizeCapital": False,
    }
    return NiftyConstituentSnapshot(
        index_code=index_code,
        source_url=source_url,
        observed_at=observed_at,
        source_sha256=source_sha256,
        constituents=ordered,
        snapshot_fingerprint=canonical_fingerprint(payload),
    )


def parse_nifty50_constituents(
    source_bytes: bytes,
    *,
    observed_at: datetime,
) -> NiftyConstituentSnapshot:
    """Parse one official NIFTY 50 CSV capture as first-observed membership evidence."""

    return _parse_constituents(source_bytes, observed_at=observed_at, index_code="NIFTY50")


def parse_nifty500_constituents(
    source_bytes: bytes,
    *,
    observed_at: datetime,
) -> NiftyConstituentSnapshot:
    """Parse one official NIFTY 500 CSV capture as first-observed membership evidence."""

    return _parse_constituents(source_bytes, observed_at=observed_at, index_code="NIFTY500")


def _select_snapshot(
    snapshots: list[NiftyConstituentSnapshot],
    *,
    as_of: datetime,
    index_code: IndexCode,
) -> NiftyConstituentSnapshot:
    _aware(as_of)
    causal = [
        snapshot
        for snapshot in snapshots
        if snapshot.index_code == index_code and snapshot.observed_at <= as_of
    ]
    if not causal:
        label = "NIFTY 50" if index_code == "NIFTY50" else "NIFTY 500"
        raise ValueError(f"no {label} constituent snapshot was observed by as_of")
    return max(causal, key=lambda snapshot: (snapshot.observed_at, snapshot.snapshot_fingerprint))


def select_nifty50_snapshot(
    snapshots: list[NiftyConstituentSnapshot],
    *,
    as_of: datetime,
) -> NiftyConstituentSnapshot:
    """Select only a NIFTY 50 snapshot that was collected by the research decision time."""

    return _select_snapshot(snapshots, as_of=as_of, index_code="NIFTY50")


def select_nifty500_snapshot(
    snapshots: list[NiftyConstituentSnapshot],
    *,
    as_of: datetime,
) -> NiftyConstituentSnapshot:
    """Select only a NIFTY 500 snapshot that was collected by the research decision time."""

    return _select_snapshot(snapshots, as_of=as_of, index_code="NIFTY500")
