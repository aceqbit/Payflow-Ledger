package com.payflow.ledger;

import com.payflow.ledger.support.SpringDbTest;
import com.payflow.ledger.support.TestDb;
import org.junit.jupiter.api.AfterAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Same invariants as the Java path, with the whole transfer running as one SQL function call. */
@SpringBootTest(properties = {"ledger.strategy=PESSIMISTIC", "ledger.transfer.sql-function=true"})
class ConcurrencyFunctionIT extends ConcurrencyTests {
    static TestDb db;

    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        if (db == null) db = TestDb.create();
        SpringDbTest.wire(r, db);
    }
    @AfterAll static void down() { db.drop(); }

    @Override TestDb db() { return db; }
    @Override String strategy() { return "PESSIMISTIC"; }
}
