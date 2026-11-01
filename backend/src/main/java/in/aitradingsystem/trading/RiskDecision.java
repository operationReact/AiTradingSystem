package in.aitradingsystem.trading;

public record RiskDecision(boolean accepted, String customerMessage) {
    public static RiskDecision accept() {
        return new RiskDecision(true, "Risk checks passed.");
    }

    public static RiskDecision reject(String customerMessage) {
        return new RiskDecision(false, customerMessage);
    }
}
