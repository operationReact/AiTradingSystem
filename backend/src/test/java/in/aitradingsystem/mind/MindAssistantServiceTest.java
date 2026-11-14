package in.aitradingsystem.mind;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class MindAssistantServiceTest {
    private final MindAssistantService assistant = new MindAssistantService(
        mock(MindCapabilityProgressService.class), mock(MindPaperPortfolioService.class),
        mock(MindPaperCycleService.class), Clock.fixed(Instant.parse("2026-11-14T04:00:00Z"), ZoneOffset.UTC));

    @Test
    void trade_requests_are_refused_without_reading_any_data() {
        for (String q : List.of("buy 10 RELIANCE now", "Place order for SBIN", "go live with real money",
                "square off my positions", "cancel order 42", "execute the trade")) {
            var answer = assistant.ask(UUID.randomUUID(), q);
            assertThat(answer.intent()).as(q).isEqualTo("TRADE_REQUEST_REFUSED");
            assertThat(answer.sources()).as(q).isEmpty();
            assertThat(answer.liveMoney()).isFalse();
            assertThat(answer.readOnly()).isTrue();
        }
    }

    @Test
    void unknown_questions_get_help_with_no_invented_facts() {
        var answer = assistant.ask(UUID.randomUUID(), "what is the weather in mumbai");
        assertThat(answer.intent()).isEqualTo("HELP");
        assertThat(answer.sources()).isEmpty();
        assertThat(answer.answer()).contains("recorded data only");
    }

    @Test
    void rejects_empty_oversized_and_control_character_questions() {
        UUID owner = UUID.randomUUID();
        for (String bad : List.of("", "   ", "x".repeat(281), "bad\u0001input")) {
            assertThatThrownBy(() -> assistant.ask(owner, bad))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                    ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
        assertThatThrownBy(() -> assistant.ask(null, "what is my pnl"))
            .isInstanceOf(ResponseStatusException.class);
    }
}
