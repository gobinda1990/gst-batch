package gov.com.ai.webapp.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(BatchProperties.class)
public class BatchConfig {

    @Bean
    public Clock defaulterClock() {
        return Clock.system(ZoneId.of("Asia/Kolkata"));
    }
}
