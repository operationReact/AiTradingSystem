package in.aitradingsystem.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;

/** IAM temporary EC2 role credentials; never static AWS access keys. */
@Configuration
public class MarketDataLambdaConfig {
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "app.market-data", name = "provider", havingValue = "ANGEL_ONE_LAMBDA")
    LambdaClient marketDataLambdaClient() {
        return LambdaClient.builder().region(Region.AP_SOUTH_1).build();
    }
}
