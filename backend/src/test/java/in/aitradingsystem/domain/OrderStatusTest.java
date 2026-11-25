package in.aitradingsystem.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class OrderStatusTest {
    @Test
    void newOrdersMustPassRiskBeforeExecutionStates() {
        assertThat(OrderStatus.NEW.transitionTo(OrderStatus.RISK_APPROVED)).isEqualTo(OrderStatus.RISK_APPROVED);
        assertThat(OrderStatus.NEW.transitionTo(OrderStatus.REJECTED)).isEqualTo(OrderStatus.REJECTED);
        assertThat(OrderStatus.NEW.canTransitionTo(OrderStatus.READY)).isFalse();
        assertThat(OrderStatus.NEW.canTransitionTo(OrderStatus.FILLED)).isFalse();
    }

    @Test
    void approvedOrdersCannotSkipTheReadyExecutionBoundary() {
        assertThat(OrderStatus.RISK_APPROVED.canTransitionTo(OrderStatus.APPROVAL_PENDING)).isTrue();
        assertThat(OrderStatus.RISK_APPROVED.canTransitionTo(OrderStatus.READY)).isTrue();
        assertThat(OrderStatus.RISK_APPROVED.canTransitionTo(OrderStatus.FILLED)).isFalse();
    }

    @Test
    void readyAndPartialOrdersSupportFillAndTerminalAlternatives() {
        assertThat(OrderStatus.READY.canTransitionTo(OrderStatus.PARTIALLY_FILLED)).isTrue();
        assertThat(OrderStatus.READY.canTransitionTo(OrderStatus.FILLED)).isTrue();
        assertThat(OrderStatus.PARTIALLY_FILLED.canTransitionTo(OrderStatus.FILLED)).isTrue();
        assertThat(OrderStatus.PARTIALLY_FILLED.canTransitionTo(OrderStatus.CANCELLED)).isTrue();
        assertThat(OrderStatus.PARTIALLY_FILLED.canTransitionTo(OrderStatus.EXPIRED)).isTrue();
        assertThat(OrderStatus.PARTIALLY_FILLED.canTransitionTo(OrderStatus.FAILED)).isTrue();
    }

    @Test
    void terminalStatesAreFinal() {
        for (OrderStatus status : new OrderStatus[] {
                OrderStatus.FILLED, OrderStatus.REJECTED, OrderStatus.CANCELLED,
                OrderStatus.EXPIRED, OrderStatus.FAILED }) {
            assertThat(status.terminal()).isTrue();
            assertThat(status.allowedNext()).isEmpty();
            assertThatThrownBy(() -> status.transitionTo(OrderStatus.NEW))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
