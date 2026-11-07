package com.payflow.ledger.telemetry;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Spring Boot does not install the Logback OpenTelemetryAppender; this hands it the SDK so logs are exported. */
@Component
@Profile("!telemetry-off & !metrics-only")
public class OtelLogbackInstaller implements InitializingBean {
    private final OpenTelemetry openTelemetry;

    public OtelLogbackInstaller(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
    }

    @Override
    public void afterPropertiesSet() {
        OpenTelemetryAppender.install(openTelemetry);
    }
}
