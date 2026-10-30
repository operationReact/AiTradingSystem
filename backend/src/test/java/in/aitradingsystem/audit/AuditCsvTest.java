package in.aitradingsystem.audit;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class AuditCsvTest {
    @ParameterizedTest
    @ValueSource(strings={"=1+1","+cmd()","-cmd()","@SUM(A1)","  =1","\t=1","\rhello","\ufeff=1"})
    void spreadsheetFormulaPrefixesAreNeutralizedEvenAfterWhitespace(String value) {
        assertThat(AuditQueryService.cell(value)).startsWith("\"'").endsWith("\"");
    }
    @Test
    void quotesAndCommasRemainOneQuotedCsvCell() {
        assertThat(AuditQueryService.cell("hello,\"world\"")).isEqualTo("\"hello,\"\"world\"\"\"");
    }
}
