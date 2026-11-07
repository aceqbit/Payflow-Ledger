package com.payflow.ledger;

import com.payflow.ledger.support.TestDb;
import com.payflow.ledger.support.TestTokens;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminIT {
    static TestDb db;

    @AfterAll static void down() { db.drop(); }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        if (db == null) db = TestDb.create();
        r.add("spring.datasource.url", () -> db.url);
        r.add("spring.datasource.username", () -> TestDb.USER);
        r.add("spring.datasource.password", () -> TestDb.PASSWORD);
        r.add("management.otlp.metrics.export.enabled", () -> "false");
        r.add("ledger.verify.enabled", () -> "false");
    }

    @LocalServerPort int port;

    HttpResponse<String> verify(String token) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/admin/verify"))
                .header("Authorization", "Bearer " + token).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void adminVerifyReturnsCounts() throws Exception {
        var r = verify(TestTokens.admin());
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("\"global_sum\":0"), r.body());
        assertTrue(r.body().contains("\"duplicate_charge\":0"));
        assertTrue(r.body().contains("chainBreaks"));
    }

    @Test void merchantTokenCannotCallAdmin() throws Exception {
        assertEquals(403, verify(TestTokens.forMerchant("m1")).statusCode());
    }
}
