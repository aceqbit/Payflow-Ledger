package com.payflow.ledger.ledger;

import com.payflow.ledger.telemetry.LedgerMetrics;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Writes the outbox row in the money transaction. mode=retain keeps it for the polling relay;
 * mode=delete inserts then deletes in the same transaction so Debezium CDC sees the insert.
 */
@Component
public class OutboxWriter {
    private final CountingJdbc jdbc;
    private final LedgerMetrics metrics;
    private final boolean deleteMode;

    public OutboxWriter(CountingJdbc jdbc, LedgerMetrics metrics, @Value("${ledger.outbox.mode:retain}") String mode) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.deleteMode = "delete".equalsIgnoreCase(mode);
    }

    public void write(String aggregateType, String aggregateId, String type, long seq, String merchantId,
                      long amountMinor, String currency, String payloadJson) {
        UUID id = jdbc.sql("""
                INSERT INTO outbox (aggregate_type, aggregate_id, type, seq, merchant_id, amount_minor, currency, payload, traceparent)
                VALUES (:at, :aid, :type, :seq, :m, :amt, :cur, CAST(:payload AS jsonb), :tp) RETURNING id""")
                .param("at", aggregateType).param("aid", aggregateId).param("type", type).param("seq", seq)
                .param("m", merchantId).param("amt", amountMinor).param("cur", currency)
                .param("payload", payloadJson).param("tp", traceparent())
                .query(UUID.class).single();
        if (deleteMode) {
            jdbc.sql("DELETE FROM outbox WHERE id = :id").param("id", id).update();
        }
        metrics.outboxWrite(type);
    }

    /** W3C trace context of the current request span, or null when there is none. */
    public static String traceparent() {
        SpanContext c = Span.current().getSpanContext();
        if (!c.isValid()) return null;
        return "00-" + c.getTraceId() + "-" + c.getSpanId() + "-" + c.getTraceFlags().asHex();
    }
}
