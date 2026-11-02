package in.aitradingsystem.news.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class NewsAnalysisParserTest {
    private final NewsAnalysisParser parser = new NewsAnalysisParser(new ObjectMapper());

    private static final String VALID = """
        {"sentiment":"positive","directionScore":0.55,"category":"EARNINGS","confidence":0.82,
         "materiality":0.77,"horizon":"MULTI_DAY","novelty":0.64,
         "contradiction":false,"ambiguity":true,
         "impactedSymbols":["reliance","TCS"],"evidence":"Net profit rose 14% year on year.",
         "risks":["Single-quarter result"]}
        """;

    @Test
    void acceptsValidOutputAndNormalizesCase() {
        NewsAnalysisParser.Result r = parser.parse(VALID);
        assertThat(r.sentiment()).isEqualTo("POSITIVE");
        assertThat(r.directionScore()).isEqualByComparingTo(new BigDecimal("0.550"));
        assertThat(r.category()).isEqualTo("EARNINGS");
        assertThat(r.confidence()).isEqualByComparingTo(new BigDecimal("0.820"));
        assertThat(r.materiality()).isEqualByComparingTo(new BigDecimal("0.770"));
        assertThat(r.horizon()).isEqualTo("MULTI_DAY");
        assertThat(r.novelty()).isEqualByComparingTo(new BigDecimal("0.640"));
        assertThat(r.contradiction()).isFalse();
        assertThat(r.ambiguity()).isTrue();
        assertThat(r.impactedSymbols()).containsExactly("RELIANCE", "TCS");
        assertThat(r.risks()).containsExactly("Single-quarter result");
    }

    @Test
    void stripsMarkdownFences() {
        String fenced = "```json\n" + VALID + "```";
        assertThat(parser.parse(fenced).sentiment()).isEqualTo("POSITIVE");
    }

    @Test
    void rejectsUnsupportedSentimentCategoryAndHorizon() {
        assertInvalid(VALID.replace("positive", "bullish"));
        assertInvalid(VALID.replace("EARNINGS", "RUMOUR"));
        assertInvalid(VALID.replace("MULTI_DAY", "SOMEDAY"));
    }

    @Test
    void rejectsScoresOutsideDeclaredRanges() {
        assertInvalid(VALID.replace("0.82", "1.5"));
        assertInvalid(VALID.replace("0.55", "-1.5"));
        assertInvalid(VALID.replace("0.77", "1.2"));
        assertInvalid(VALID.replace("0.64", "-0.1"));
    }

    @Test
    void rejectsInvalidSymbol() {
        assertInvalid(VALID.replace("\"TCS\"", "\"TCS; DROP TABLE\""));
    }

    @Test
    void rejectsTooManyRisks() {
        assertInvalid(VALID.replace("[\"Single-quarter result\"]", "[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\"]"));
    }

    @Test
    void rejectsMissingEvidenceAndNonJson() {
        assertInvalid(VALID.replace("\"evidence\":\"Net profit rose 14% year on year.\"", "\"evidence\":\" \""));
        assertInvalid("Sure! Here is the analysis.");
        assertInvalid("");
    }

    private static void assertInvalid(String raw) {
        assertThatThrownBy(() -> new NewsAnalysisParser(new ObjectMapper()).parse(raw))
            .isInstanceOfSatisfying(AiProviderException.class, e -> {
                assertThat(e.code()).isEqualTo(AiErrorCode.INVALID_RESPONSE);
                assertThat(e.retryable()).isFalse();
            });
    }
}
