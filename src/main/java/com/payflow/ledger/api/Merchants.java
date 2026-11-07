package com.payflow.ledger.api;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

final class Merchants {
    private Merchants() {}

    /** merchant_id is taken only from the token claim. */
    static String of(Jwt jwt) {
        String m = jwt.getClaimAsString("merchant_id");
        if (m == null || m.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "token has no merchant_id claim");
        }
        return m;
    }
}
