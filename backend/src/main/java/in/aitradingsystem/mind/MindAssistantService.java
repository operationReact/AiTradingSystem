package in.aitradingsystem.mind;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only "Ask MintTick Mind" assistant.
 *
 * <p>Answers are deterministic and grounded only in server state: the audited progress registry,
 * the owner's virtual PAPER portfolio, and the recorded PAPER cycle journal. Each answer names the
 * sources it used and their timestamps. The assistant never places, changes or suggests orders;
 * trade-like requests are refused explicitly. There is no language model in this path, so it
 * cannot invent facts.
 */
@Service
public class MindAssistantService {
    public static final String CONTRACT_VERSION = "mind.assistant.v1";
    static final int MAX_QUESTION = 280;

    private final MindCapabilityProgressService progress;
    private final MindPaperPortfolioService portfolio;
    private final MindPaperCycleService cycles;
    private final Clock clock;

    public MindAssistantService(MindCapabilityProgressService progress, MindPaperPortfolioService portfolio,
                                MindPaperCycleService cycles, Clock clock) {
        this.progress = progress;
        this.portfolio = portfolio;
        this.cycles = cycles;
        this.clock = clock;
    }

    public record Source(String name, String endpoint, Instant asOf) {}

    public record Answer(String contractVersion, String intent, String answer, List<Source> sources,
                         boolean readOnly, boolean liveMoney, Instant generatedAt) {}

    public Answer ask(UUID owner, String rawQuestion) {
        if (owner == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Customer required");
        if (rawQuestion == null || rawQuestion.isBlank() || rawQuestion.length() > MAX_QUESTION
                || rawQuestion.chars().anyMatch(c -> c < 32 && c != '\n' && c != '\t')) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Question must be 1-280 printable characters");
        }
        String q = rawQuestion.strip().toLowerCase(Locale.ROOT);
        Instant now = clock.instant();

        if (containsAny(q, "buy ", "sell ", "place order", "place an order", "execute", "short ", "cancel order",
                "square off", "exit my", "close my position", "go live", "live order")) {
            return answer("TRADE_REQUEST_REFUSED", "Mind cannot place, change or cancel orders. Virtual PAPER "
                + "orders run only through an owner-initiated strategy cycle in a funded PAPER room; LIVE orders are "
                + "not available here.", List.of(), now);
        }
        if (containsAny(q, "risk", "limit", "policy", "allowed", "max loss")) {
            var p = portfolio.inspect(owner);
            List<String> lines = new ArrayList<>();
            lines.add("Enforced PAPER risk policy (server rules, not adjustable here):");
            lines.add("- Per-trade risk budget: " + p.riskPolicy().perTradeRiskBudget());
            lines.add("- Max single-order exposure: " + p.riskPolicy().maxSingleOrderExposure());
            lines.add("- Quote freshness: " + p.riskPolicy().maxQuoteAge());
            lines.add("- Signal vs quote divergence: " + p.riskPolicy().maxSignalQuoteDivergence());
            lines.add("- LIVE trading: " + p.riskPolicy().liveTrading());
            return answer("RISK_POLICY", String.join("\n", lines),
                List.of(new Source("PAPER risk policy", "/api/customer/mind/v1/paper-portfolio", p.generatedAt())), now);
        }
        if (containsAny(q, "pnl", "p&l", "profit", "loss", "return", "earn", "made", "lost")) {
            var p = portfolio.inspect(owner);
            var journal = cycles.journal(owner, 50);
            String text = "Virtual PAPER results (not real money):\n"
                + "- Realized P&L: " + format(p.realizedPnlPaise()) + "\n"
                + "- Unrealized P&L: " + (p.unrealizedPnlPaise() == null
                    ? "not available until every position has a fresh mark"
                    : format(p.unrealizedPnlPaise())) + "\n"
                + "- Closed PAPER trades: " + journal.performance().closedTrades()
                + (journal.performance().sampleTooSmall()
                    ? " (sample below " + journal.performance().minimumMeaningfulSample()
                      + " trades, so no performance conclusion is meaningful)" : "");
            return answer("PAPER_PNL", text, List.of(
                new Source("PAPER portfolio", "/api/customer/mind/v1/paper-portfolio", p.generatedAt()),
                new Source("PAPER journal", "/api/customer/mind/v1/paper-journal", journal.generatedAt())), now);
        }
        if (containsAny(q, "position", "holding", "open trade", "invested")) {
            var p = portfolio.inspect(owner);
            if (p.positions().isEmpty()) {
                return answer("PAPER_POSITIONS", "No open virtual PAPER positions.",
                    List.of(new Source("PAPER portfolio", "/api/customer/mind/v1/paper-portfolio", p.generatedAt())), now);
            }
            StringBuilder text = new StringBuilder("Open virtual PAPER positions:\n");
            for (var pos : p.positions()) {
                text.append("- ").append(pos.tradingSymbol()).append(" x ").append(pos.quantity())
                    .append(" (mark ").append(pos.markState()).append(")\n");
            }
            return answer("PAPER_POSITIONS", text.toString().stripTrailing(), List.of(
                new Source("PAPER portfolio", "/api/customer/mind/v1/paper-portfolio", p.generatedAt())), now);
        }
        if (containsAny(q, "blocked", "blocker", "why not", "why is", "missing", "not done", "pending")) {
            var snapshot = progress.inspect(owner);
            List<String> lines = new ArrayList<>();
            for (var item : snapshot.items()) {
                if (item.blockerCode() != null && !item.done()) {
                    lines.add("- " + item.name() + ": " + item.status() + " (" + item.blockerCode() + ")");
                }
                if (lines.size() >= 25) break;
            }
            String text = lines.isEmpty() ? "No blockers recorded in the audited registry."
                : "Blocked or pending items from the audited registry:\n" + String.join("\n", lines);
            return answer("REGISTRY_BLOCKERS", text, List.of(
                new Source("Progress registry", "/api/customer/mind/v1/progress", snapshot.generatedAt())), now);
        }
        if (containsAny(q, "stage", "progress", "done", "verified", "next step", "where are we", "status")) {
            var snapshot = progress.inspect(owner);
            String text = "PAPER stage: " + snapshot.paperStage().name() + " (" + snapshot.paperStage().status() + ").\n"
                + "No item is marked DONE unless runtime evidence exists on a known backend revision. "
                + "Backend revision: " + (snapshot.revisionKnown() ? snapshot.backendRevision() : "unknown") + ".";
            return answer("PROGRESS", text, List.of(
                new Source("Progress registry", "/api/customer/mind/v1/progress", snapshot.generatedAt())), now);
        }
        if (containsAny(q, "strateg", "signal", "candidate")) {
            var snapshot = progress.inspect(owner);
            var strategy = snapshot.summaries().stream().filter(s -> "STRATEGY".equals(s.kind())).findFirst().orElse(null);
            String text = strategy == null ? "Strategy registry not available."
                : "Strategy registry: " + strategy.denominator() + " catalog entries; " + strategy.tested()
                    + " with tested code, " + strategy.done() + " runtime verified, " + strategy.blocked()
                    + " data-blocked. Only SMA_TREND_VOLUME_V1 is wired to PAPER cycles.";
            return answer("STRATEGIES", text, List.of(
                new Source("Progress registry", "/api/customer/mind/v1/progress", snapshot.generatedAt())), now);
        }
        return answer("HELP",
            "I answer from recorded data only. Try: \"What is my PAPER P&L?\", \"Which positions are open?\", "
                + "\"What is blocked?\", \"What is the PAPER stage?\", \"How many strategies are verified?\", "
                + "\"What are the risk limits?\".",
            List.of(), now);
    }

    private static boolean containsAny(String text, String... needles) {
        for (String n : needles) if (text.contains(n)) return true;
        return false;
    }

    private static String format(long paise) {
        long abs = Math.abs(paise);
        return (paise < 0 ? "-" : "") + "Rs " + (abs / 100) + "." + String.format(Locale.ROOT, "%02d", abs % 100);
    }

    private static Answer answer(String intent, String text, List<Source> sources, Instant now) {
        return new Answer(CONTRACT_VERSION, intent, text, List.copyOf(sources), true, false, now);
    }
}
