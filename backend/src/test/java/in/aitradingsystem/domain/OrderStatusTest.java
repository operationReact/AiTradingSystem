package in.aitradingsystem.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class OrderStatusTest {
    @Test
    void newOrdersMayBeApprovedOrRejected() {
        assertThat(OrderStatus.NEW.transitionTo(OrderStatus.RISK_APPROVED)).isEqualTo(OrderStatus.RISK_APPROVED);
        assertThat(OrderStatus.NEW.transitionTo(OrderStatus.REJECTED)).isEqualTo(OrderStatus.REJECTED);
    }

    @Test
    void approvedOrdersMayOnlyFill() {
        assertThat(OrderStatus.RISK_APPROVED.transitionTo(OrderStatus.FILLED)).isEqualTo(OrderStatus.FILLED);
        assertThatThrownBy(() -> OrderStatus.RISK_APPROVED.transitionTo(OrderStatus.REJECTED))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void terminalStatesAreFinal() {
        assertThat(OrderStatus.REJECTED.allowedNext()).isEmpty();
        assertThat(OrderStatus.FILLED.allowedNext()).isEmpty();
        assertThatThrownBy(() -> OrderStatus.FILLED.transitionTo(OrderStatus.NEW))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void newOrdersCannotSkipRiskApproval() {
        assertThat(OrderStatus.NEW.canTransitionTo(OrderStatus.FILLED)).isFalse();
    }
}
