package com.careparse.payerlab;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

public final class ClientAssertionGenerator {
    private ClientAssertionGenerator() {
    }

    public static void main(String[] args) throws Exception {
        String tokenEndpoint = args.length > 0 ? args[0] : "http://localhost:8080/auth/token";
        Path privateKeyPath = args.length > 1 ? Path.of(args[1]) : Path.of("fixtures/auth/client-private.jwk.json");
        System.out.println(generate(privateKeyPath, tokenEndpoint, AuthService.CLIENT_ID, Clock.systemUTC()));
    }

    public static String generate(Path privateKeyPath, String tokenEndpoint, String clientId, Clock clock)
            throws IOException, ParseException, JOSEException {
        RSAKey privateKey = RSAKey.parse(Files.readString(privateKeyPath, StandardCharsets.UTF_8));
        Instant now = clock.instant();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(clientId)
                .subject(clientId)
                .audience(tokenEndpoint)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(AuthService.TOKEN_LIFETIME_SECONDS)))
                .jwtID(UUID.randomUUID().toString())
                .build();

        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .type(JOSEObjectType.JWT)
                        .keyID(privateKey.getKeyID())
                        .build(),
                claims);
        jwt.sign(new RSASSASigner(privateKey));
        return jwt.serialize();
    }
}
