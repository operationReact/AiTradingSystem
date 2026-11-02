package in.aitradingsystem.news.analysis;

/** Provider-neutral model call. Parsing remains shared and strict. */
public interface NewsAnalysisProvider {
    /** Stable identifier stored with each analysis, for example GEMINI or CLAUDE. */
    String name();

    String model();

    /** True only when a server-side credential is present. Never implies the provider is reachable. */
    boolean configured();

    Completion complete(String systemPrompt, String userPrompt);

    /** Provider response plus the provider's immutable request/interaction identifier when available. */
    record Completion(String text, String providerRequestId) {
        public Completion {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("completion text is required");
            }
        }
    }
}
