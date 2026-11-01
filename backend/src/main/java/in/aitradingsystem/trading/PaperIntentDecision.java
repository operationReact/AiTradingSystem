package in.aitradingsystem.trading;

public record PaperIntentDecision(boolean accepted, String idempotencyKey, String customerMessage) {}
