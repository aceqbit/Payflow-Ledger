package perf;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Closed model: -Dusers concurrent users for -Dduration seconds (default 300). Warm-up is excluded by the
 * report (first 60 s), not by the load profile, so the injection profile is identical in every run.
 * A failed request (5xx, timeout, connection error) is retried with the SAME Idempotency-Key.
 * -Ddist = uniform | zipf | hot (account distribution, fixed seed).
 */
public class TransferSimulation extends Simulation {
    static final int USERS = Integer.getInteger("users", 50);
    static final int DURATION = Integer.getInteger("duration", 300);
    static final String DIST = System.getProperty("dist", "uniform");
    static final String BASE = System.getProperty("base", "http://localhost:8080");
    static final String GRAFANA = System.getProperty("grafana", "http://localhost:3000");
    static final String SECRET = System.getenv().getOrDefault("JWT_SECRET", "dev-only-secret-change-me-0123456789abcdef");
    static final String RUN_ID = System.getenv().getOrDefault("RUN_ID", "adhoc");
    static final long SEED = 42L;

    /** merchant -> account ids, loaded from the seed CSV (merchant_id,account_id). */
    static final Map<String, List<String>> ACCOUNTS = new HashMap<>();
    static final List<String> MERCHANTS = new ArrayList<>();
    static final Map<String, String> TOKENS = new HashMap<>();

    static {
        try {
            for (String line : Files.readAllLines(Path.of(System.getProperty("accounts", "results/accounts.csv")))) {
                String[] p = line.split(",");
                if (p.length < 2 || p[0].equals("merchant_id")) continue;
                ACCOUNTS.computeIfAbsent(p[0], k -> new ArrayList<>()).add(p[1]);
            }
        } catch (Exception e) {
            throw new IllegalStateException("run scripts/run.sh (it exports results/accounts.csv from the seed)", e);
        }
        MERCHANTS.addAll(ACCOUNTS.keySet());
        MERCHANTS.sort(String::compareTo);
        for (String m : MERCHANTS) TOKENS.put(m, token(m));
    }

    static String token(String merchant) {
        try {
            Base64.Encoder b = Base64.getUrlEncoder().withoutPadding();
            String h = b.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            long exp = System.currentTimeMillis() / 1000 + 86_400;
            String p = b.encodeToString(("{\"sub\":\"gatling\",\"merchant_id\":\"" + merchant + "\",\"exp\":" + exp + "}").getBytes(StandardCharsets.UTF_8));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return h + "." + p + "." + b.encodeToString(mac.doFinal((h + "." + p).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Index into a merchant's account list under the selected skew. Zipf 80/20: 80% of picks hit the first 20%. */
    static int pick(int n, Random r) {
        return switch (DIST) {
            case "zipf" -> r.nextDouble() < 0.8 ? r.nextInt(Math.max(1, n / 5)) : r.nextInt(n);
            case "hot" -> r.nextDouble() < 0.5 ? 0 : r.nextInt(n);
            default -> r.nextInt(n);
        };
    }

    static Map<String, Object> next() {
        Random r = ThreadLocalRandom.current();
        String m = MERCHANTS.get(r.nextInt(MERCHANTS.size()));
        List<String> accts = ACCOUNTS.get(m);
        int from = r.nextInt(accts.size()), to = pick(accts.size(), r);
        if (to == from) to = (to + 1) % accts.size();
        Map<String, Object> s = new HashMap<>();
        s.put("token", TOKENS.get(m));
        s.put("body", "{\"fromAccountId\":\"" + accts.get(from) + "\",\"toAccountId\":\"" + accts.get(to)
                + "\",\"amountMinor\":" + (1 + r.nextInt(100)) + ",\"currency\":\"USD\"}");
        s.put("key", UUID.randomUUID().toString());
        return s;
    }

    HttpProtocolBuilder protocol = http.baseUrl(BASE).acceptHeader("application/json").contentTypeHeader("application/json").shareConnections();

    ChainBuilder transfer = exec(session -> session.setAll(next()))
            .tryMax(5).on(
                    exec(http("transfer").post("/v1/transfers")
                            .header("Authorization", "Bearer #{token}")
                            .header("Idempotency-Key", "#{key}")   // same key on every retry
                            .body(StringBody("#{body}"))
                            .check(status().in(201, 409))));

    ScenarioBuilder scn = scenario("transfers").during(Duration.ofSeconds(DURATION)).on(transfer);

    {
        setUp(scn.injectClosed(constantConcurrentUsers(USERS).during(Duration.ofSeconds(DURATION)))).protocols(protocol);
    }

    @Override
    public void before() {
        annotate("start");
    }

    @Override
    public void after() {
        annotate("end");
    }

    /** Best-effort Grafana annotation so every dashboard shows where the run began and ended. */
    private static void annotate(String what) {
        try {
            String body = "{\"tags\":[\"gatling\",\"run_id:" + RUN_ID + "\"],\"text\":\"gatling " + what + " " + RUN_ID + " users=" + USERS + " dist=" + DIST + "\"}";
            HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(GRAFANA + "/api/annotations"))
                    .header("Content-Type", "application/json").timeout(Duration.ofSeconds(2))
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
            // Grafana is optional (native runs have none)
        }
    }
}
