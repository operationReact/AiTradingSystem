package in.aitradingsystem.marketdata;

import java.util.List;

public record CandlePage(List<MarketCandle> items, int page, int size, long totalItems, int totalPages,
                         boolean hasNext) {}
