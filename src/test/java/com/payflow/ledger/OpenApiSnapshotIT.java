package com.payflow.ledger;

import com.payflow.ledger.support.SpringDbTest;
import com.payflow.ledger.support.TestDb;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The published API must match docs/openapi.json. An intentional API change is recorded by running
 * mvn verify -Dit.test=OpenApiSnapshotIT -Dopenapi.update=true and committing the new file.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiSnapshotIT {
    static TestDb db;

    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        if (db == null) db = TestDb.create();
        SpringDbTest.wire(r, db);
    }
    @AfterAll static void down() { db.drop(); }

    @LocalServerPort int port;

    @Test void apiMatchesCommittedSpec() throws Exception {
        ObjectMapper om = JsonMapper.builder().build();
        String live = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs")).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        ObjectNode liveTree = (ObjectNode) om.readTree(live);
        liveTree.remove("servers"); // the port differs per run

        Path file = Path.of("docs", "openapi.json");
        if (Boolean.getBoolean("openapi.update")) {
            Files.writeString(file, om.writerWithDefaultPrettyPrinter().writeValueAsString(liveTree) + "\n");
            return;
        }
        ObjectNode committed = (ObjectNode) om.readTree(Files.readString(file));
        committed.remove("servers");
        assertEquals(committed, liveTree, "API changed: regenerate docs/openapi.json with -Dopenapi.update=true and review the diff");
    }
}
