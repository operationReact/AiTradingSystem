package in.aitradingsystem.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * AI news analysis configuration. API keys are read from the environment only and are never
 * returned by any endpoint or written to logs.
 */
@Validated
@ConfigurationProperties(prefix = "app.news")
public record NewsProperties(
    @NotNull @Pattern(regexp = "NONE|GEMINI|CLAUDE") String provider,
    @NotNull String geminiModel,
    @NotNull String claudeModel,
    String geminiApiKey,
    String claudeApiKey,
    @Min(100) @Max(60_000) int connectTimeoutMs,
    @Min(1000) @Max(120_000) int readTimeoutMs,
    @Min(1) @Max(3) int maxAttempts
) {}
