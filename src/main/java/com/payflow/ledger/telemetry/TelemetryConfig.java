package com.payflow.ledger.telemetry;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;

@Configuration
public class TelemetryConfig {
    // Virtual-thread metrics (jvm.threads.virtual.*) are auto-registered by Boot when micrometer-java21 is present.

    /** Async work keeps its trace context across thread hops. */
    @Bean
    ContextPropagatingTaskDecorator contextPropagatingTaskDecorator() {
        return new ContextPropagatingTaskDecorator();
    }
}
