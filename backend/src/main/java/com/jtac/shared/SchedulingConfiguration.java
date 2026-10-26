package com.jtac.shared;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Background jobs (email outbox, webhook deliveries, cleanup). Disable with
 * {@code jtac.jobs.enabled=false} (e.g. for one-shot administration commands and tests
 * that drive the dispatchers explicitly).
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
public class SchedulingConfiguration {

    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "jtac.jobs.enabled", havingValue = "true", matchIfMissing = true)
    static class Jobs {
    }
}
