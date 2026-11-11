package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

class CustomerHistoricalProgressControllerTest {
    @TempDir Path temporary;

    private JwtAuthenticationToken jwt(String subject) {
        Jwt token = Jwt.withTokenValue("test")
                .header("alg", "RS256").claim("sub", subject).build();
        return new JwtAuthenticationToken(token);
    }

    private String safePayload() {
        String years = IntStream.rangeClosed(2006, 2026)
                .mapToObj(y -> "{\"year\":" + y + ",\"candles\":0}")
                .collect(Collectors.joining(","));
        return "{\"schema\":\"minttick.owner.history.progress.v1\","
                + "\"generatedAt\":\"" + OffsetDateTime.now() + "\","
                + "\"source\":\"ANGEL_ONE\",\"status\":\"IDLE\","
                + "\"task\":{\"interval\":\"ONE_DAY\",\"candles\":56172,"
                + "\"yearCoverageVerified\":false,\"listingHistoryVerified\":false,"
                + "\"years\":[" + years + "]},"
                + "\"scheduler\":{\"cadence\":\"HOURLY_OFF_HOURS\"},"
                + "\"recentRuns\":[],\"recentRequests\":[]}";
    }

    @Test void onlyMatchingCognitoOwnerCanRead() throws Exception {
        Path snapshot = temporary.resolve("progress.json");
        Files.writeString(snapshot, safePayload(), StandardCharsets.UTF_8);
        var controller = new CustomerHistoricalProgressController("owner-sub",
                snapshot.toString(), new ObjectMapper());
        assertEquals(56172, controller.progress(jwt("owner-sub")).getBody()
                .path("task").path("candles").asInt());
        ResponseStatusException other = assertThrows(ResponseStatusException.class,
                () -> controller.progress(jwt("another-customer")));
        assertEquals(HttpStatus.FORBIDDEN, other.getStatusCode());
        ResponseStatusException noOwner = assertThrows(ResponseStatusException.class,
                () -> new CustomerHistoricalProgressController("", snapshot.toString(),
                        new ObjectMapper()).progress(jwt("owner-sub")));
        assertEquals(HttpStatus.FORBIDDEN, noOwner.getStatusCode());
    }

    @Test void absentOrMalformedSnapshotNeverReturnsFabricatedProgress() throws Exception {
        Path snapshot = temporary.resolve("progress.json");
        var controller = new CustomerHistoricalProgressController("owner",
                snapshot.toString(), new ObjectMapper());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                assertThrows(ResponseStatusException.class,
                        () -> controller.progress(jwt("owner"))).getStatusCode());
        Files.writeString(snapshot, "{\"task\":{\"candles\":123}}");
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                assertThrows(ResponseStatusException.class,
                        () -> controller.progress(jwt("owner"))).getStatusCode());
    }

    @Test void symlinkOrUnverifiedCoverageIsRejected() throws Exception {
        Path target = temporary.resolve("target.json");
        Files.writeString(target, safePayload().replace(
            "\"yearCoverageVerified\":false", "\"yearCoverageVerified\":true"));
        var controller = new CustomerHistoricalProgressController("owner",
                target.toString(), new ObjectMapper());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                assertThrows(ResponseStatusException.class,
                        () -> controller.progress(jwt("owner"))).getStatusCode());
        Path symlink = temporary.resolve("alias.json");
        Files.createSymbolicLink(symlink, target);
        var symlinkController = new CustomerHistoricalProgressController("owner",
                symlink.toString(), new ObjectMapper());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                assertThrows(ResponseStatusException.class,
                        () -> symlinkController.progress(jwt("owner"))).getStatusCode());
    }
}
