package com.payflow.ledger.api;

import com.payflow.ledger.ledger.LedgerException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/** Reads only; always scoped by the merchant from the JWT. */
@RestController
public class AccountController {
    public record Balance(UUID accountId, String currency, long balanceMinor) {}

    public record StatementLine(UUID entryId, UUID txnId, long amountMinor, Instant createdAt) {}

    public record Statement(UUID accountId, List<StatementLine> entries, String nextCursor) {}

    private final JdbcClient jdbc;

    public AccountController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/v1/accounts/{id}/balance")
    Balance balance(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return jdbc.sql("SELECT id, currency, balance_minor FROM accounts WHERE id = :id AND merchant_id = :m")
                .param("id", id).param("m", Merchants.of(jwt))
                .query((rs, n) -> new Balance(rs.getObject(1, UUID.class), rs.getString(2).trim(), rs.getLong(3)))
                .optional().orElseThrow(() -> new LedgerException(404, "ACCOUNT_NOT_FOUND", "account not found"));
    }

    /** Keyset pagination on (created_at, id), newest first. cursor = "epochMicros:entryId". */
    @GetMapping("/v1/accounts/{id}/statement")
    Statement statement(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                        @RequestParam(defaultValue = "50") int limit, @RequestParam(required = false) String cursor) {
        String merchant = Merchants.of(jwt);
        int n = Math.max(1, Math.min(limit, 200));
        balance(jwt, id); // 404 when the account is not this merchant's

        OffsetDateTime afterTs = OffsetDateTime.of(9999, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        UUID afterId = new UUID(-1L, -1L);
        if (cursor != null && !cursor.isBlank()) {
            String[] p = cursor.split(":", 2);
            try {
                long micros = Long.parseLong(p[0]);
                afterTs = OffsetDateTime.ofInstant(Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L),
                        Math.floorMod(micros, 1_000_000L) * 1000), ZoneOffset.UTC);
                afterId = UUID.fromString(p[1]);
            } catch (RuntimeException e) {
                throw new LedgerException(400, "BAD_CURSOR", "malformed cursor");
            }
        }
        List<StatementLine> rows = jdbc.sql("""
                SELECT e.id, e.txn_id, e.amount_minor, e.created_at FROM ledger_entries e
                JOIN accounts a ON a.id = e.account_id AND a.merchant_id = :m
                WHERE e.account_id = :acc AND (e.created_at, e.id) < (:ts, :eid)
                ORDER BY e.created_at DESC, e.id DESC LIMIT :lim""")
                .param("m", merchant).param("acc", id).param("ts", afterTs).param("eid", afterId).param("lim", n + 1)
                .query((rs, i) -> new StatementLine(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getLong(3),
                        rs.getObject(4, OffsetDateTime.class).toInstant()))
                .list();
        String next = null;
        if (rows.size() > n) {
            rows = rows.subList(0, n);
            StatementLine last = rows.get(n - 1);
            next = com.payflow.ledger.ledger.HashChain.toEpochMicros(last.createdAt().truncatedTo(ChronoUnit.MICROS)) + ":" + last.entryId();
        }
        return new Statement(id, rows, next);
    }
}
