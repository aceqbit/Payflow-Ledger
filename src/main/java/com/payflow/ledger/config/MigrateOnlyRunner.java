package com.payflow.ledger.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** `--ledger.migrate-only=true` applies Flyway migrations and exits; used to prepare the ledger_seed template DB. */
@Component
@ConditionalOnProperty(name = "ledger.migrate-only", havingValue = "true")
public class MigrateOnlyRunner implements ApplicationRunner {
    private final ConfigurableApplicationContext ctx;

    public MigrateOnlyRunner(ConfigurableApplicationContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void run(ApplicationArguments args) {
        System.exit(SpringApplication.exit(ctx, () -> 0));
    }
}
