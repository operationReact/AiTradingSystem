package in.aitradingsystem.tradingrooms;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

class CustomerPaperFnoFeatureGateTest {
    @Test
    void ordersAreNotConstructedWithoutBothPaperAndV3Feed() {
        for (var type : new Class<?>[]{
                CustomerPaperFnoOrderService.class,
                CustomerPaperFnoOrderController.class,
                CustomerPaperFnoMarketController.class}) {
            var annotation = type.getAnnotation(ConditionalOnProperty.class);
            assertThat(annotation).as(type.getSimpleName()).isNotNull();
            assertThat(annotation.name()).containsExactlyInAnyOrder(
                    "minttick.upstox-fno.paper-enabled",
                    "minttick.upstox-fno.v3-feed-enabled");
            assertThat(annotation.havingValue()).isEqualTo("true");
        }
    }
}
