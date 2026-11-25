package in.aitradingsystem.provider.upstox;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Customer-scoped, read-only comparison against the owner's verified EC2 EIP.
 * This never updates broker registration, refreshes OAuth, or authorizes orders.
 * The EC2 IP has been confirmed allocated and associated by the owner in AWS.
 * A broker match does not prove live session/entitlement or order egress route.
 */
public final class UpstoxRegisteredIpAssessment {
    private static final String VERIFIED_EC2_EIP = "3.108.130.17";
    private UpstoxRegisteredIpAssessment() {}

    public record Result(
        String expectedEc2Ip, String status, boolean registrationMatchesEc2,
        boolean liveExecutionAuthorized, String explanation) {}

    public static Result assess(JsonNode data) {
        if (data == null || !data.isObject()) {
            return result("UNVERIFIED", false,
                "Broker registered-IP response was unavailable or invalid");
        }
        final String primary = ipText(data.path("primary_ip"));
        final String secondary = ipText(data.path("secondary_ip"));
        if (primary == null && secondary == null) {
            return result("NOT_REGISTERED", false,
                "No broker-registered primary or secondary IP was returned");
        }
        if (VERIFIED_EC2_EIP.equals(primary) || VERIFIED_EC2_EIP.equals(secondary)) {
            return result("REGISTERED_MATCH", true,
                "Registered IP matches verified EC2 EIP; order execution remains disabled");
        }
        return result("REGISTERED_MISMATCH", false,
            "Broker-registered addresses do not match verified EC2 EIP");
    }

    private static String ipText(JsonNode node) {
        if (!node.isTextual()) return null;
        String value = node.asText();
        if (value.isBlank() || value.length() > 45) return null;
        // Do not echo untrusted provider text in the response.
        return value;
    }

    private static Result result(String status, boolean match, String note) {
        return new Result(VERIFIED_EC2_EIP, status, match, false, note);
    }
}
