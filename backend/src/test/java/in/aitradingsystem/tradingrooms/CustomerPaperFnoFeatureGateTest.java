package in.aitradingsystem.tradingrooms;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

class CustomerPaperFnoFeatureGateTest {
    @Test
    void realOptionsReadOnlyV3FeedIsIndependentOfPaperExecution() {
        var market = CustomerPaperFnoMarketController.class
            .getAnnotation(ConditionalOnProperty.class);
        assertThat(market).isNotNull();
        assertThat(market.name()).containsExactly("minttick.upstox-fno.v3-feed-enabled");
        assertThat(market.havingValue()).isEqualTo("true");
        // This controller is read-only; never route orders or mark
        // nonexistent exchange entitlements as verified.
        assertThat(java.util.Arrays.stream(
                CustomerPaperFnoMarketController.class.getDeclaredMethods())
            .noneMatch(m -> m.isAnnotationPresent(
                org.springframework.web.bind.annotation.PostMapping.class))).isTrue();
    }

    @Test
    void ordersStillRequireBothPaperAndRealV3Feed() {
        for (var type : new Class<?>[]{
                CustomerPaperFnoOrderService.class,
                CustomerPaperFnoOrderController.class}) {
            var annotation = type.getAnnotation(ConditionalOnProperty.class);
            assertThat(annotation).as(type.getSimpleName()).isNotNull();
            assertThat(annotation.name()).containsExactlyInAnyOrder(
                    "minttick.upstox-fno.paper-enabled",
                    "minttick.upstox-fno.v3-feed-enabled");
            assertThat(annotation.havingValue()).isEqualTo("true");
        }
    }
}
