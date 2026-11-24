package in.aitradingsystem.news;

import in.aitradingsystem.config.NewsProperties;
import in.aitradingsystem.news.analysis.AiProviderException;
import in.aitradingsystem.news.analysis.GeminiNewsAnalysisProvider;
import in.aitradingsystem.news.analysis.NewsAnalysisParser;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Explicit ADMIN-only diagnostic, using the configured production provider and no real article.
 * No background calls, retries, database writes, trading actions or caller-controlled prompts.
 * Cooldown and concurrency limits apply to this backend process only.
 */
@RestController
@RequestMapping("/api/admin/market-intelligence")
public class GeminiConnectivityProbeController {
    private static final Duration COOLDOWN = Duration.ofMinutes(5);
    private final NewsProperties properties;
    private final GeminiNewsAnalysisProvider provider;
    private final NewsAnalysisParser parser;
    private final AtomicBoolean running = new AtomicBoolean();
    private Instant nextAllowed = Instant.MIN;

    public GeminiConnectivityProbeController(NewsProperties properties,
            GeminiNewsAnalysisProvider provider, NewsAnalysisParser parser) {
        this.properties = properties;
        this.provider = provider;
        this.parser = parser;
    }

    @PostMapping("/gemini-connectivity")
    public ResponseEntity<ProbeResult> verify() {
        if (!"GEMINI".equals(properties.provider()) || !provider.configured()) {
            return response(new ProbeResult(Instant.now(), provider.model(),
                "NOT_CONFIGURED_OR_SELECTED", false, false, 0));
        }
        if (!running.compareAndSet(false, true)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Gemini probe already running");
        }
        try {
            Instant now = Instant.now();
            if (now.isBefore(nextAllowed)) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Gemini probe cooldown");
            }
            nextAllowed = now.plus(COOLDOWN);
            long start = System.nanoTime();
            String challenge = "MINTTICK-DIAGNOSTIC-" + UUID.randomUUID();
            try {
                var completion = provider.complete(NewsAnalysisService.SYSTEM_PROMPT,
                    "Connectivity diagnostic only; fictional input, not news. "
                    + "Return neutral OTHER classification, directionScore 0, confidence 1, "
                    + "materiality 0, horizon UNKNOWN, novelty 0, contradiction false, "
                    + "ambiguity false, impactedSymbols [], risks []. "
                    + "The evidence field must equal exactly: " + challenge);
                var parsed = parser.parse(completion.text());
                boolean idPresent = completion.providerRequestId() != null
                    && !completion.providerRequestId().isBlank();
                boolean verified = idPresent && challenge.equals(parsed.evidence())
                    && parsed.impactedSymbols().isEmpty() && "NEUTRAL".equals(parsed.sentiment())
                    && "OTHER".equals(parsed.category()) && parsed.directionScore().signum() == 0;
                return response(new ProbeResult(Instant.now(), provider.model(),
                    verified ? "VERIFIED_STRUCTURED_RESPONSE" : "CHALLENGE_OR_REQUEST_ID_INVALID",
                    true, verified, elapsed(start)));
            } catch (AiProviderException e) {
                return response(new ProbeResult(Instant.now(), provider.model(),
                    e.code().name(), true, false, elapsed(start)));
            }
        } finally {
            running.set(false);
        }
    }

    private static long elapsed(long start) {
        return Math.max(0L, (System.nanoTime() - start) / 1_000_000);
    }

    private static ResponseEntity<ProbeResult> response(ProbeResult result) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    /** No prompts, response bodies, provider request IDs or secret values are exposed. */
    public record ProbeResult(Instant observedAt, String model, String outcome,
        boolean providerAttempted, boolean structuredConnectivityVerified, long latencyMs) {}
}
