from __future__ import annotations

import argparse
import json
from datetime import datetime
from pathlib import Path

from .event_source import NewsResearchExport, load_news_export
from .exports import export_feature_dataset
from .features import build_feature_row
from .fundamental_source import FundamentalResearchExport, load_fundamental_export
from .market_source import MarketResearchExport, load_market_export


def build_research_dataset(
    *,
    market: MarketResearchExport,
    news: NewsResearchExport | None,
    fundamentals: FundamentalResearchExport | None,
    output_dir: Path,
    dataset_version: str,
    code_sha: str,
):
    bars = market.to_market_bars()
    if not bars:
        raise ValueError("market export contains no candles")

    events = news.to_event_records() if news is not None else []
    fundamental_rows = (
        fundamentals.to_fundamental_snapshots() if fundamentals is not None else []
    )

    rows = []
    market_window = []
    for bar in bars:
        market_window.append(bar)
        rows.append(
            build_feature_row(
                symbol=bar.symbol,
                as_of=bar.observed_at,
                bars=market_window,
                events=events,
                fundamentals=fundamental_rows,
            )
        )

    source_versions = {
        "marketExportContract": market.contract_version,
        "instrumentCatalogue": str(market.catalogue_version),
        "marketProviders": ",".join(sorted(market.provider_sources)),
        "interval": market.interval,
    }
    if news is not None:
        source_versions["newsExportContract"] = news.contract_version
        source_versions["newsProviders"] = ",".join(sorted(news.provider_sources))
    if fundamentals is not None:
        source_versions["fundamentalExportContract"] = fundamentals.contract_version
        source_versions["fundamentalProviders"] = ",".join(
            sorted(fundamentals.provider_sources)
        )

    return export_feature_dataset(
        rows,
        output_dir=output_dir,
        dataset_version=dataset_version,
        code_sha=code_sha,
        source_versions=source_versions,
    )


def _validate_shared_window(
    market: MarketResearchExport,
    news: NewsResearchExport | None,
    fundamentals: FundamentalResearchExport | None,
) -> None:
    for name, export in (("news", news), ("fundamentals", fundamentals)):
        if export is None:
            continue
        if export.from_inclusive > market.from_inclusive:
            raise ValueError(f"{name} export starts after the market research window")
        if export.to_exclusive < market.to_exclusive:
            raise ValueError(f"{name} export ends before the market research window")


def build_from_paths(
    *,
    market_path: Path,
    news_path: Path | None,
    fundamentals_path: Path | None,
    output_dir: Path,
    dataset_version: str,
    code_sha: str,
):
    market = load_market_export(market_path)
    news = load_news_export(news_path) if news_path is not None else None
    fundamentals = (
        load_fundamental_export(fundamentals_path)
        if fundamentals_path is not None
        else None
    )
    _validate_shared_window(market, news, fundamentals)
    return build_research_dataset(
        market=market,
        news=news,
        fundamentals=fundamentals,
        output_dir=output_dir,
        dataset_version=dataset_version,
        code_sha=code_sha,
    )


def main() -> None:
    parser = argparse.ArgumentParser(
        description=(
            "Build a deterministic point-in-time feature dataset from market, news, "
            "and fundamental exports"
        )
    )
    parser.add_argument("market_json", type=Path)
    parser.add_argument("output_dir", type=Path)
    parser.add_argument("--news-json", type=Path)
    parser.add_argument("--fundamentals-json", type=Path)
    parser.add_argument("--dataset-version", required=True)
    parser.add_argument("--code-sha", required=True)
    args = parser.parse_args()

    manifest = build_from_paths(
        market_path=args.market_json,
        news_path=args.news_json,
        fundamentals_path=args.fundamentals_json,
        output_dir=args.output_dir,
        dataset_version=args.dataset_version,
        code_sha=args.code_sha,
    )
    print(json.dumps(manifest.model_dump(mode="json"), sort_keys=True))


if __name__ == "__main__":
    main()
