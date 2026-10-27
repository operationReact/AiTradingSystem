package in.aitradingsystem.news.analysis;

/** Provider-neutral model call. Implementations return raw model text; parsing is shared and strict. */
public interface NewsAnalysisProvider {
    /** Stable identifier stored with each analysis, for example GEMINI or CLAUDE. */
    String name();

    String model();

    /** True only when a server-side credential is present. Never implies the provider is reachable. */
    boolean configured();

    String complete(String systemPrompt, String userPrompt);
}
