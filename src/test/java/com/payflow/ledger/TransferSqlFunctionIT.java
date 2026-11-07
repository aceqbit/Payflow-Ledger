package com.payflow.ledger;

import com.payflow.ledger.support.SpringDbTest;
import com.payflow.ledger.support.TestDb;
import org.junit.jupiter.api.AfterAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "ledger.transfer.sql-function=true")
class TransferSqlFunctionIT extends TransferTests {
    static TestDb db;

    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        if (db == null) db = TestDb.create();
        SpringDbTest.wire(r, db);
    }
    @AfterAll static void down() { db.drop(); }

    @Override TestDb dbRef() { return db; }
    @Override double expectedRoundTrips() { return 1.0; }
}
