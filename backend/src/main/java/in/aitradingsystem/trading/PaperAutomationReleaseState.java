package in.aitradingsystem.trading;

public record PaperAutomationReleaseState(
    boolean paperAvailable,
    boolean automationAvailable,
    boolean liveAvailable,
    boolean brokerSubmissionAvailable
) {
    public static PaperAutomationReleaseState current() {
        return new PaperAutomationReleaseState(true, true, false, false);
    }
}
