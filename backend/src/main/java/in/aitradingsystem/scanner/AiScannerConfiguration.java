package in.aitradingsystem.scanner;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiScannerConfiguration {

    @Bean
    @ConditionalOnMissingBean(RankedOpportunityProvider.class)
    RankedOpportunityProvider rankedOpportunityProvider() {
        return List::of;
    }
}
