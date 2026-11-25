package in.aitradingsystem.news.feature;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/**
 * Maps the analyzer's advisory category to an event class, and decides whether the event opens a
 * blackout window. Pure and deterministic. The category is the model's classification of article
 * text, so the class is advisory and never a trade instruction.
 */
public final class NewsEventClassifier {
    public static final BigDecimal BLACKOUT_MATERIALITY = new BigDecimal("0.70");
    public static final Duration BLACKOUT_WINDOW = Duration.ofDays(1);

    private NewsEventClassifier() {}

    public enum EventClass {
        EARNINGS_RESULT,
        REGULATORY_ACTION,
        CORPORATE_ACTION,
        MANAGEMENT_CHANGE,
        MACRO_BACKDROP,
        SECTOR_THEME,
        GENERAL,
        UNCLASSIFIED
    }

    public static EventClass classify(String category) {
        if (category == null) return EventClass.UNCLASSIFIED;
        return switch (category) {
            case "EARNINGS" -> EventClass.EARNINGS_RESULT;
            case "REGULATORY" -> EventClass.REGULATORY_ACTION;
            case "CORPORATE_ACTION" -> EventClass.CORPORATE_ACTION;
            case "MANAGEMENT" -> EventClass.MANAGEMENT_CHANGE;
            case "MACRO" -> EventClass.MACRO_BACKDROP;
            case "SECTOR" -> EventClass.SECTOR_THEME;
            case "OTHER" -> EventClass.GENERAL;
            default -> EventClass.UNCLASSIFIED;
        };
    }

    /** Company-specific, high-materiality events open a blackout. Macro, sector and general news do not. */
    public static boolean blackoutEligible(EventClass eventClass) {
        return eventClass == EventClass.EARNINGS_RESULT
            || eventClass == EventClass.REGULATORY_ACTION
            || eventClass == EventClass.CORPORATE_ACTION;
    }

    /** Blackout end for one item, or null when the item does not open a blackout. */
    public static Instant blackoutUntil(EventClass eventClass, BigDecimal materiality, Instant publishedAt) {
        if (publishedAt == null || materiality == null || !blackoutEligible(eventClass)) return null;
        return materiality.compareTo(BLACKOUT_MATERIALITY) >= 0 ? publishedAt.plus(BLACKOUT_WINDOW) : null;
    }

    public static boolean inBlackout(Instant blackoutUntil, Instant asOf) {
        return blackoutUntil != null && asOf.isBefore(blackoutUntil);
    }
}
