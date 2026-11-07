package com.payflow.ledger.support;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.nio.charset.StandardCharsets;
import java.util.Date;

/** Mints HS256 test tokens with the dev secret. */
public final class TestTokens {
    public static final String SECRET = "dev-only-secret-change-me-0123456789abcdef";

    private TestTokens() {}

    public static String forMerchant(String merchantId) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject("test-" + merchantId).claim("merchant_id", merchantId)
                    .expirationTime(new Date(System.currentTimeMillis() + 3_600_000)).build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
