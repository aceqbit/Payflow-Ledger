package com.payflow.ledger.api;

import com.payflow.ledger.ledger.TransferCommand;
import com.payflow.ledger.ledger.TransferService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TransferController {
    private final TransferService transfers;

    public TransferController(TransferService transfers) {
        this.transfers = transfers;
    }

    @PostMapping(value = "/v1/transfers", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> create(@AuthenticationPrincipal Jwt jwt,
                                  @RequestHeader("Idempotency-Key") String key,
                                  @Valid @RequestBody TransferRequest req) {
        if (key.isBlank() || key.length() > 255) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Idempotency-Key must be 1-255 chars");
        }
        var cmd = new TransferCommand(Merchants.of(jwt), req.fromAccountId(), req.toAccountId(), req.amountMinor(), req.currency());
        var r = transfers.transfer(cmd, key);
        return ResponseEntity.status(r.status()).contentType(MediaType.APPLICATION_JSON).body(r.body());
    }
}
