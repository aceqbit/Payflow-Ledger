package com.payflow.ledger.api;

import com.payflow.ledger.ledger.ApiResult;
import com.payflow.ledger.payment.PaymentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class PaymentController {
    public record CreatePayment(@NotNull UUID payerAccountId, @NotNull UUID payeeAccountId,
                                @Positive @Max(1_000_000_000_000_000L) long amountMinor,
                                @NotNull @Pattern(regexp = "[A-Z]{3}") String currency) {}

    public record AmountRequest(@Positive @Max(1_000_000_000_000_000L) Long amountMinor) {}

    private final PaymentService payments;

    public PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    @PostMapping(value = "/v1/payments", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> create(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
                                  @Valid @RequestBody CreatePayment req) {
        return reply(payments.create(Merchants.of(jwt), Keys.check(key), req.payerAccountId(), req.payeeAccountId(), req.amountMinor(), req.currency()));
    }

    @PostMapping("/v1/payments/{id}/authorize")
    ResponseEntity<String> authorize(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key, @PathVariable UUID id) {
        return reply(payments.authorize(Merchants.of(jwt), Keys.check(key), id));
    }

    @PostMapping("/v1/payments/{id}/capture")
    ResponseEntity<String> capture(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key, @PathVariable UUID id,
                                   @Valid @RequestBody(required = false) AmountRequest req) {
        return reply(payments.capture(Merchants.of(jwt), Keys.check(key), id, req == null ? null : req.amountMinor()));
    }

    @PostMapping("/v1/payments/{id}/refund")
    ResponseEntity<String> refund(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key, @PathVariable UUID id,
                                  @Valid @RequestBody(required = false) AmountRequest req) {
        return reply(payments.refund(Merchants.of(jwt), Keys.check(key), id, req == null ? null : req.amountMinor()));
    }

    @PostMapping("/v1/payments/{id}/void")
    ResponseEntity<String> voidPayment(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key, @PathVariable UUID id) {
        return reply(payments.voidPayment(Merchants.of(jwt), Keys.check(key), id));
    }

    static ResponseEntity<String> reply(ApiResult r) {
        return ResponseEntity.status(r.status()).contentType(MediaType.APPLICATION_JSON).body(r.body());
    }
}
