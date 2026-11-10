package in.aitradingsystem.mind;

import in.aitradingsystem.marketdata.ExpectedCandleSlot;
import in.aitradingsystem.marketdata.MarketCandle;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Conservative, fixed-protocol evaluation of a PRECOMMITTED prospective research
 * candidate. No order, portfolio, capital allocation or broker functionality.
 *
 * This is NOT exchange-attested data, a validated trading edge, or a complete
 * Indian brokerage/statutory fee calculation. All missing bars fail closed.
 */
@Component
public final class MindProspectiveOutcomeEvaluator {
    private static final Duration BAR = Duration.ofMinutes(5);
    private static final Duration SOURCE_LIMIT = Duration.ofMinutes(15);
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal FEE_PER_SIDE = new BigDecimal("0.0010");
    private static final BigDecimal SLIPPAGE_PER_SIDE = new BigDecimal("0.0005");

    public record Outcome(Instant entryCandleAt, Instant exitCandleAt,
                          BigDecimal rawEntry, BigDecimal rawExit,
                          String exitReason, int sourceBars,
                          String outcomeSourceDigest, BigDecimal grossReturnPercent,
                          BigDecimal illustrativeNetReturnPercent) {}

    /**
     * Requires exactly the exchange-calendar bars from the next five-minute
     * open until exit. A stop wins any ambiguous stop-and-target OHLC bar.
     * Missing/late/synthetic data is never converted into a successful result.
     */
    public Optional<Outcome> evaluate(
            MindProspectiveOutcomeRepository.Receipt receipt,
            List<MarketCandle> candles,
            List<ExpectedCandleSlot> slots,
            Instant evaluatedAt) {
        if (receipt == null || candles == null || slots == null ||
            evaluatedAt == null || receipt.signalCandleAt() == null ||
            receipt.recordedAt() == null || receipt.memberSlot() < 1 ||
            receipt.instrumentId() <= 0 || receipt.catalogueVersion() == null ||
            !"NSE".equals(receipt.exchange()) ||
            receipt.symbolToken() == null || receipt.symbolToken().isBlank() ||
            receipt.tradingSymbol() == null ||
            receipt.candidateFingerprint() == null ||
            !receipt.candidateFingerprint().matches("[a-f0-9]{64}") ||
            receipt.originalSourceDigest() == null ||
            !receipt.originalSourceDigest().matches("[a-f0-9]{64}") ||
            receipt.referencePrice() == null || receipt.stop() == null ||
            receipt.target() == null || receipt.referencePrice().signum() <= 0 ||
            receipt.stop().signum() <= 0 ||
            receipt.stop().compareTo(receipt.referencePrice()) >= 0 ||
            receipt.target().compareTo(receipt.referencePrice()) <= 0 ||
            candles.isEmpty()) return Optional.empty();

        Instant first = receipt.signalCandleAt().plus(BAR);
        int firstIndex = -1;
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i).timestamp().equals(first)) {
                firstIndex = i;
                break;
            }
        }
        if (firstIndex < 0 || candles.size() > slots.size() - firstIndex ||
            evaluatedAt.isBefore(receipt.recordedAt()) ||
            receipt.recordedAt().isBefore(receipt.signalCandleAt().plus(BAR)) ||
            !receipt.recordedAt().isBefore(receipt.signalCandleAt().plus(Duration.ofMinutes(15))))
            return Optional.empty();

        String source = candles.getFirst().source();
        if (source == null || source.isBlank() ||
            source.matches("(?i).*(TEST|FIXTURE|MOCK|DEMO|SYNTHETIC).*"))
            return Optional.empty();

        StringBuilder canonical = new StringBuilder("MIND_PROSPECTIVE_OUTCOME_SOURCE_V1");
        append(canonical, receipt.cohortId().toString(), receipt.candidateFingerprint(),
            receipt.originalSourceDigest(), receipt.signalCandleAt().toString());
        BigDecimal entry = candles.getFirst().open();
        if (entry == null || entry.signum() <= 0) return Optional.empty();

        for (int i = 0; i < candles.size(); i++) {
            MarketCandle bar = candles.get(i);
            Instant expectedAt = slots.get(firstIndex + i).timestamp();
            if (!validSource(receipt, bar, source, expectedAt, evaluatedAt)) {
                return Optional.empty();
            }
            append(canonical, Long.toString(bar.instrumentId()), bar.exchange(),
                bar.tradingSymbol(), bar.symbolToken(), bar.catalogueVersion().toString(),
                bar.interval(), bar.candleTime().toString(),
                decimal(bar.open()), decimal(bar.high()), decimal(bar.low()),
                decimal(bar.close()), Long.toString(bar.volume()),
                bar.source(), bar.fetchedAt().toString());

            BigDecimal exit = null;
            String reason = null;
            // Gap through a threshold executes at the worse/actual open.
            if (bar.open().compareTo(receipt.stop()) <= 0) {
                exit = bar.open();
                reason = "STOP";
            } else if (bar.open().compareTo(receipt.target()) >= 0) {
                exit = bar.open();
                reason = "TARGET";
            } else if (bar.low().compareTo(receipt.stop()) <= 0) {
                exit = receipt.stop();
                reason = "STOP";
            } else if (bar.high().compareTo(receipt.target()) >= 0) {
                exit = receipt.target();
                reason = "TARGET";
            } else if (firstIndex + i == slots.size() - 1) {
                exit = bar.close();
                reason = "SESSION_CLOSE";
            }
            if (exit != null) {
                BigDecimal gross = exit.subtract(entry).multiply(HUNDRED)
                    .divide(entry, 4, RoundingMode.HALF_UP);
                BigDecimal chargedEntry = entry.multiply(ONE.add(SLIPPAGE_PER_SIDE))
                    .multiply(ONE.add(FEE_PER_SIDE));
                BigDecimal chargedExit = exit.multiply(ONE.subtract(SLIPPAGE_PER_SIDE))
                    .multiply(ONE.subtract(FEE_PER_SIDE));
                BigDecimal net = chargedExit.subtract(chargedEntry).multiply(HUNDRED)
                    .divide(chargedEntry, 4, RoundingMode.HALF_UP);
                return Optional.of(new Outcome(first, bar.candleTime(), entry, exit,
                    reason, i + 1, sha256(canonical.toString()), gross, net));
            }
        }
        // A truncated series cannot be marked resolved.
        return Optional.empty();
    }

    private static boolean validSource(
            MindProspectiveOutcomeRepository.Receipt receipt, MarketCandle c,
            String source, Instant expectedAt, Instant evaluatedAt) {
        return c != null && c.instrumentId() == receipt.instrumentId() &&
            receipt.exchange().equals(c.exchange()) &&
            receipt.tradingSymbol().equals(c.tradingSymbol()) &&
            receipt.symbolToken().equals(c.symbolToken()) &&
            receipt.catalogueVersion().equals(c.catalogueVersion()) &&
            "FIVE_MINUTE".equals(c.interval()) &&
            expectedAt.equals(c.candleTime()) && source.equals(c.source()) &&
            c.fetchedAt() != null &&
            !c.fetchedAt().isBefore(c.candleTime().plus(BAR)) &&
            !c.fetchedAt().isAfter(c.candleTime().plus(SOURCE_LIMIT)) &&
            !c.fetchedAt().isAfter(evaluatedAt) &&
            !c.fetchedAt().isBefore(receipt.recordedAt()) &&
            c.open() != null && c.high() != null &&
            c.low() != null && c.close() != null &&
            c.open().signum() > 0 && c.low().signum() > 0 &&
            c.high().compareTo(c.low()) >= 0 &&
            c.high().compareTo(c.open()) >= 0 &&
            c.high().compareTo(c.close()) >= 0 &&
            c.low().compareTo(c.open()) <= 0 &&
            c.low().compareTo(c.close()) <= 0 &&
            c.volume() > 0;
    }

    private static String decimal(BigDecimal n) {
        return n.stripTrailingZeros().toPlainString();
    }

    private static void append(StringBuilder out, String... fields) {
        out.append('\n');
        for (String field : fields) out.append(field.length()).append('#').append(field);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
