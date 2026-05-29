package com.careparse.payerlab;

import ca.uhn.fhir.context.FhirContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.UriType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

public final class AuthServlet extends HttpServlet {
    private static final String JSON = "application/json;charset=UTF-8";

    private final FhirContext fhirContext;
    private final AuthService authService;
    private final ObjectMapper mapper = new ObjectMapper();

    public AuthServlet(FhirContext fhirContext, AuthService authService) {
        this.fhirContext = fhirContext;
        this.authService = authService;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getRequestURI();
        if ("/fhir/.well-known/smart-configuration".equals(path)) {
            writeJson(response, 200, authService.smartConfiguration(request));
            return;
        }

        if ("/auth/.well-known/openid-configuration".equals(path)) {
            writeJson(response, 200, authService.openIdConfiguration(request));
            return;
        }

        if ("/auth/jwks.json".equals(path)) {
            writeJson(response, 200, authService.payerJwksJson());
            return;
        }

        if ("/fhir/metadata".equals(path)) {
            FhirHttpResponses.writeRawResource(
                    response,
                    200,
                    fhirContext.newJsonParser().encodeResourceToString(capabilityStatement(request)));
            return;
        }

        FhirHttpResponses.writeOperationOutcome(
                fhirContext,
                response,
                HttpServletResponse.SC_NOT_FOUND,
                "not-supported",
                "The payer simulator does not expose this auth endpoint.");
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!"/auth/token".equals(request.getRequestURI())) {
            FhirHttpResponses.writeOperationOutcome(
                    fhirContext,
                    response,
                    HttpServletResponse.SC_NOT_FOUND,
                    "not-supported",
                    "The payer simulator does not expose this auth endpoint.");
            return;
        }

        String formBody = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            AuthService.TokenResponse token = authService.issueToken(request, formBody);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("access_token", token.accessToken());
            body.put("token_type", "Bearer");
            body.put("expires_in", token.expiresIn());
            body.put("scope", token.scope());
            writeJson(response, 200, mapper.writeValueAsString(body));
        } catch (AuthService.OAuthException e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", e.error());
            body.put("error_description", e.getMessage());
            writeJson(response, "invalid_client".equals(e.error()) ? 401 : 400, mapper.writeValueAsString(body));
        }
    }

    private CapabilityStatement capabilityStatement(HttpServletRequest request) {
        String origin = AuthService.origin(request);
        CapabilityStatement statement = new CapabilityStatement();
        statement.setId("payerlab-capability");
        statement.setUrl(AuthService.fhirBase(origin) + "/metadata");
        statement.setName("CareParsePayerLabCapabilityStatement");
        statement.setTitle("CareParse PayerLab FHIR CapabilityStatement");
        statement.setStatus(Enumerations.PublicationStatus.ACTIVE);
        statement.setDate(new Date());
        statement.setKind(CapabilityStatement.CapabilityStatementKind.INSTANCE);
        statement.setFhirVersion(Enumerations.FHIRVersion._4_0_1);
        statement.addFormat("json");

        CapabilityStatement.CapabilityStatementRestComponent rest = statement.addRest();
        rest.setMode(CapabilityStatement.RestfulCapabilityMode.SERVER);
        rest.getSecurity().addService(new CodeableConcept(new Coding(
                "http://terminology.hl7.org/CodeSystem/restful-security-service",
                "SMART-on-FHIR",
                "SMART-on-FHIR")));
        Extension oauthUris = new Extension();
        oauthUris.setUrl("http://fhir-registry.smarthealthit.org/StructureDefinition/oauth-uris");
        oauthUris.addExtension("token", new UriType(AuthService.tokenEndpoint(origin)));
        rest.getSecurity().addExtension(oauthUris);
        return statement;
    }

    private static void writeJson(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(JSON);
        response.getWriter().write(body);
    }
}
