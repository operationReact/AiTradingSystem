package in.aitradingsystem.trading;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class PaperTradeHistoryService {
    private final Map<String, List<PaperClosedTrade>> history = new ConcurrentHashMap<>();

    public void record(String accountId, PaperPosition closed) {
        if (accountId == null || accountId.isBlank()) throw new IllegalArgumentException("Account is required.");
        if (closed == null || closed.open()) throw new IllegalArgumentException("Only closed PAPER positions can be recorded.");
        var trade = new PaperClosedTrade(closed.orderId(), closed.symbol(), closed.side(), closed.quantity(),
            closed.entryPrice(), closed.lastPrice(), closed.realizedPnl());
        history.computeIfAbsent(accountId, ignored -> new ArrayList<>()).add(trade);
    }

    public List<PaperClosedTrade> history(String accountId) {
        return List.copyOf(history.getOrDefault(accountId, List.of()));
    }
}
