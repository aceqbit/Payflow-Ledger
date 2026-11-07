package com.payflow.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class PayflowLedgerApplication {
    public static void main(String[] args) {
        SpringApplication.run(PayflowLedgerApplication.class, args);
    }
}
