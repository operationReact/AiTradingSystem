package in.aitradingsystem.backtest;

import java.util.List;

/** Stable paginated response for filtered backtest history queries. */
public record BacktestRunPage(
        List<BacktestRunSummary> items,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public BacktestRunPage {
        items = items == null ? List.of() : List.copyOf(items);
        if (page < 0) throw new IllegalArgumentException("page must be non-negative");
        if (size < 1) throw new IllegalArgumentException("size must be positive");
        if (totalElements < 0) throw new IllegalArgumentException("totalElements must be non-negative");
        if (totalPages < 0) throw new IllegalArgumentException("totalPages must be non-negative");
    }
}
