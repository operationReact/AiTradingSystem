package in.aitradingsystem.news.analysis;

/** Provider-neutral model call. Parsing remains shared and strict. */
public interface NewsAnalysisProvider {
    /** Stable identifier stored with each analysis, for example GEMINI or CLAUDE. */
    String name();

    String model();

    /** True only when a server-side credential is present. Never implies the provider is reachable. */
    boolean configured();

    Completion complete(String systemPrompt, String userPrompt);

    /** Provider response, immutable request identifier and server-reported token usage when available. */
    record Completion(
        String text,
        String providerRequestId,
        Integer inputTokens,
        Integer outputTokens,
        Integer totalTokens
    ) {
        public Completion {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("completion text is required");
            }
            if (inputTokens != null && inputTokens < 0) throw new IllegalArgumentException("inputTokens cannot be negative");
            if (outputTokens != null && outputTokens < 0) throw new IllegalArgumentException("outputTokens cannot be negative");
            if (totalTokens != null && totalTokens < 0) throw new IllegalArgumentException("totalTokens cannot be negative");
        }

        public Completion(String text, String providerRequestId) {
            this(text, providerRequestId, null, null, null);
        }
    }
}
