package com.careparse.payerlab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AuthService {
    public static final String CLIENT_ID = "payerlab-provider-backend";
    public static final String CLIENT_ASSERTION_TYPE = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";
    public static final int TOKEN_LIFETIME_SECONDS = 300;
    public static final Set<String> ALLOWED_SCOPES = Set.of(
            "system/Coverage.rs",
            "system/Questionnaire.rs",
            "system/QuestionnaireResponse.c",
            "system/Claim.c",
            "system/Claim.rs",
            "system/ClaimResponse.rs");

    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock;
    private final RSAKey payerPrivateKey;
    private final String payerJwksJson;
    private final JWKSet clientPublicJwks;
    private final Set<String> usedClientAssertionIds = ConcurrentHashMap.newKeySet();

    public AuthService(Path authRoot) {
        this(authRoot, Clock.systemUTC());
    }

    AuthService(Path authRoot, Clock clock) {
        this.clock = clock;
        try {
            this.payerPrivateKey = RSAKey.parse(Files.readString(authRoot.resolve("payer-private.jwk.json"), StandardCharsets.UTF_8));
            this.payerJwksJson = Files.readString(authRoot.resolve("payer-jwks.json"), StandardCharsets.UTF_8);
            this.clientPublicJwks = JWKSet.parse(Files.readString(authRoot.resolve("client-public-jwks.json"), StandardCharsets.UTF_8));
        } catch (IOException | ParseException e) {
            throw new IllegalStateException("Unable to load SMART/OIDC auth fixtures from " + authRoot, e);
        }
    }

    public String payerJwksJson() {
        return payerJwksJson;
    }

    public String smartConfiguration(HttpServletRequest request) throws IOException {
        String origin = origin(request);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("issuer", issuer(origin));
        config.put("jwks_uri", origin + "/auth/jwks.json");
        config.put("token_endpoint", tokenEndpoint(origin));
        config.put("token_endpoint_auth_methods_supported", List.of("private_key_jwt"));
        config.put("grant_types_supported", List.of("client_credentials"));
        config.put("scopes_supported", ALLOWED_SCOPES.stream().sorted().toList());
        config.put("response_types_supported", List.of());
        config.put("capabilities", List.of(
                "client-confidential-asymmetric",
                "permission-v2"));
        return mapper.writeValueAsString(config);
    }

    public String openIdConfiguration(HttpServletRequest request) throws IOException {
        String origin = origin(request);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("issuer", issuer(origin));
        config.put("jwks_uri", origin + "/auth/jwks.json");
        config.put("token_endpoint", tokenEndpoint(origin));
        config.put("grant_types_supported", List.of("client_credentials"));
        config.put("token_endpoint_auth_methods_supported", List.of("private_key_jwt"));
        config.put("scopes_supported", ALLOWED_SCOPES.stream().sorted().toList());
        config.put("response_types_supported", List.of());
        config.put("subject_types_supported", List.of("public"));
        config.put("id_token_signing_alg_values_supported", List.of("RS256"));
        return mapper.writeValueAsString(config);
    }

    public TokenResponse issueToken(HttpServletRequest request, String formBody) throws OAuthException {
        Map<String, String> form = parseForm(formBody);
        requireEquals(form, "grant_type", "client_credentials", "unsupported_grant_type");
        requireEquals(form, "client_assertion_type", CLIENT_ASSERTION_TYPE, "invalid_client");

        String requestedScope = requirePresent(form, "scope", "invalid_scope");
        Set<String> requestedScopes = splitScopes(requestedScope);
        if (requestedScopes.isEmpty() || !ALLOWED_SCOPES.containsAll(requestedScopes)) {
            throw new OAuthException("invalid_scope", "Requested scopes are not pre-authorized for this client.");
        }

        String tokenEndpoint = tokenEndpoint(origin(request));
        validateClientAssertion(requirePresent(form, "client_assertion", "invalid_client"), tokenEndpoint);

        Instant now = clock.instant();
        Instant expiration = now.plusSeconds(TOKEN_LIFETIME_SECONDS);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer(origin(request)))
                .subject(CLIENT_ID)
                .audience(fhirBase(origin(request)))
                .claim("scope", String.join(" ", requestedScopes))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiration))
                .jwtID(UUID.randomUUID().toString())
                .build();

        SignedJWT token = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .type(JOSEObjectType.JWT)
                        .keyID(payerPrivateKey.getKeyID())
                        .build(),
                claims);
        try {
            token.sign(new RSASSASigner(payerPrivateKey));
        } catch (JOSEException e) {
            throw new OAuthException("server_error", "Unable to sign access token.");
        }

        return new TokenResponse(token.serialize(), String.join(" ", requestedScopes), TOKEN_LIFETIME_SECONDS);
    }

    public AccessDecision authorize(HttpServletRequest request, Set<String> requiredScopes) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return AccessDecision.denied(401, "invalid_token", "A Bearer access token is required.");
        }

        SignedJWT token;
        try {
            token = SignedJWT.parse(authorization.substring("Bearer ".length()));
        } catch (ParseException e) {
            return AccessDecision.denied(401, "invalid_token", "Bearer access token is not a signed JWT.");
        }

        try {
            JWK key = JWKSet.parse(payerJwksJson).getKeyByKeyId(token.getHeader().getKeyID());
            if (!(key instanceof RSAKey rsaKey) || !token.verify(new RSASSAVerifier(rsaKey))) {
                return AccessDecision.denied(401, "invalid_token", "Bearer access token signature is invalid.");
            }

            JWTClaimsSet claims = token.getJWTClaimsSet();
            Instant now = clock.instant();
            if (!issuer(origin(request)).equals(claims.getIssuer())
                    || !claims.getAudience().contains(fhirBase(origin(request)))) {
                return AccessDecision.denied(401, "invalid_token", "Bearer access token issuer or audience is invalid.");
            }
            if (claims.getExpirationTime() == null || !claims.getExpirationTime().toInstant().isAfter(now)) {
                return AccessDecision.denied(401, "invalid_token", "Bearer access token is expired.");
            }

            Set<String> tokenScopes = splitScopes(claims.getStringClaim("scope"));
            if (!tokenScopes.containsAll(requiredScopes)) {
                return AccessDecision.denied(403, "insufficient_scope", "Bearer access token does not include the required scope.");
            }

            return AccessDecision.permitted();
        } catch (ParseException | JOSEException e) {
            return AccessDecision.denied(401, "invalid_token", "Bearer access token cannot be validated.");
        }
    }

    private void validateClientAssertion(String serializedAssertion, String tokenEndpoint) throws OAuthException {
        SignedJWT assertion;
        try {
            assertion = SignedJWT.parse(serializedAssertion);
        } catch (ParseException e) {
            throw new OAuthException("invalid_client", "Client assertion is not a signed JWT.");
        }

        try {
            JWK clientKey = clientPublicJwks.getKeyByKeyId(assertion.getHeader().getKeyID());
            if (!(clientKey instanceof RSAKey rsaKey) || !assertion.verify(new RSASSAVerifier(rsaKey))) {
                throw new OAuthException("invalid_client", "Client assertion signature is invalid.");
            }

            JWTClaimsSet claims = assertion.getJWTClaimsSet();
            Instant now = clock.instant();
            if (!CLIENT_ID.equals(claims.getIssuer()) || !CLIENT_ID.equals(claims.getSubject())) {
                throw new OAuthException("invalid_client", "Client assertion iss and sub must match the registered client id.");
            }
            if (!claims.getAudience().contains(tokenEndpoint)) {
                throw new OAuthException("invalid_client", "Client assertion audience must be the token endpoint.");
            }
            if (claims.getExpirationTime() == null || !claims.getExpirationTime().toInstant().isAfter(now)) {
                throw new OAuthException("invalid_client", "Client assertion is expired.");
            }
            if (claims.getExpirationTime().toInstant().isAfter(now.plusSeconds(TOKEN_LIFETIME_SECONDS))) {
                throw new OAuthException("invalid_client", "Client assertion expiration is too far in the future.");
            }
            if (claims.getJWTID() == null || claims.getJWTID().isBlank()) {
                throw new OAuthException("invalid_client", "Client assertion must include a jti.");
            }
            if (!usedClientAssertionIds.add(claims.getJWTID())) {
                throw new OAuthException("invalid_client", "Client assertion jti has already been used.");
            }
        } catch (ParseException | JOSEException e) {
            throw new OAuthException("invalid_client", "Client assertion cannot be validated.");
        }
    }

    private static void requireEquals(Map<String, String> form, String key, String expected, String error) throws OAuthException {
        String value = requirePresent(form, key, error);
        if (!expected.equals(value)) {
            throw new OAuthException(error, "Unsupported or invalid " + key + ".");
        }
    }

    private static String requirePresent(Map<String, String> form, String key, String error) throws OAuthException {
        String value = form.get(key);
        if (value == null || value.isBlank()) {
            throw new OAuthException(error, "Missing required parameter: " + key + ".");
        }
        return value;
    }

    private static Map<String, String> parseForm(String formBody) {
        Map<String, String> parsed = new LinkedHashMap<>();
        if (formBody == null || formBody.isBlank()) {
            return parsed;
        }

        for (String pair : formBody.split("&")) {
            int separator = pair.indexOf('=');
            String rawKey = separator >= 0 ? pair.substring(0, separator) : pair;
            String rawValue = separator >= 0 ? pair.substring(separator + 1) : "";
            parsed.put(
                    URLDecoder.decode(rawKey, StandardCharsets.UTF_8),
                    URLDecoder.decode(rawValue, StandardCharsets.UTF_8));
        }
        return parsed;
    }

    static Set<String> splitScopes(String scope) {
        if (scope == null || scope.isBlank()) {
            return Set.of();
        }
        return new LinkedHashSet<>(Arrays.stream(scope.split("\\s+"))
                .filter(value -> !value.isBlank())
                .toList());
    }

    public static String origin(HttpServletRequest request) {
        String host = request.getHeader("Host");
        if (host == null || host.isBlank()) {
            host = request.getServerName() + ":" + request.getServerPort();
        }
        return request.getScheme() + "://" + host;
    }

    public static String issuer(String origin) {
        return origin + "/auth";
    }

    public static String tokenEndpoint(String origin) {
        return origin + "/auth/token";
    }

    public static String fhirBase(String origin) {
        return origin + "/fhir";
    }

    public record TokenResponse(String accessToken, String scope, int expiresIn) {
    }

    public record AccessDecision(boolean allowed, int status, String error, String diagnostics) {
        static AccessDecision permitted() {
            return new AccessDecision(true, 200, "", "");
        }

        static AccessDecision denied(int status, String error, String diagnostics) {
            return new AccessDecision(false, status, error, diagnostics);
        }
    }

    public static final class OAuthException extends Exception {
        private final String error;

        OAuthException(String error, String message) {
            super(message);
            this.error = error;
        }

        public String error() {
            return error;
        }
    }
}
