package perf;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Chaos load: -Drequests transfers (default 1,000,000). Every 5xx, timeout or connection error (including the app being
 * killed with kill -9) is retried with the SAME Idempotency-Key, up to 20 attempts, so the ledger must end with exactly one
 * charge per key no matter how many times a request was replayed.
 */
public class ChaosSimulation extends Simulation {
    static final long REQUESTS = Long.getLong("requests", 1_000_000L);
    static final int USERS = Integer.getInteger("users", 50);
    static final String BASE = System.getProperty("base", "http://localhost:8080");
    static final AtomicLong STARTED = new AtomicLong();

    HttpProtocolBuilder protocol = http.baseUrl(BASE).acceptHeader("application/json").contentTypeHeader("application/json")
            .shareConnections().disableCaching();

    ChainBuilder one = exec(session -> session.setAll(TransferSimulation.next()).set("try", 0))
            .tryMax(20).on(
                    exec(session -> session.set("try", session.getInt("try") + 1))
                            .doIf(session -> session.getInt("try") > 1).then(pause(Duration.ofMillis(250)))   // let a killed app come back
                            .exec(http("transfer").post("/v1/transfers")
                                    .header("Authorization", "Bearer #{token}")
                                    .header("Idempotency-Key", "#{key}")   // same key on every retry
                                    .body(StringBody("#{body}"))
                                    .check(status().in(201, 409))));

    ScenarioBuilder scn = scenario("chaos transfers")
            .asLongAs(session -> STARTED.incrementAndGet() <= REQUESTS).on(one);

    {
        setUp(scn.injectClosed(constantConcurrentUsers(USERS).during(Duration.ofHours(6)))).protocols(protocol);
    }
}
