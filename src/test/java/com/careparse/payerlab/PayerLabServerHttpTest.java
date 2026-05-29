package com.careparse.payerlab;

import ca.uhn.fhir.context.FhirContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.ClaimResponse;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Questionnaire;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PayerLabServerHttpTest {
    private static final String ALL_SCOPES = String.join(" ", AuthService.ALLOWED_SCOPES);

    private final FhirContext fhirContext = FhirContext.forR4();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private Server server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws Exception {
        server = PayerLabServer.createServer(0, Path.of("fixtures/payer"));
        server.start();
        int port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
        baseUrl = "http://localhost:" + port;
    }

    @Test
    void executesCrdDtrPasWorkflowAgainstPayerApis() throws Exception {
        HttpResponse<String> discovery = getJson("/cds-services");
        assertEquals(200, discovery.statusCode());
        JsonNode services = objectMapper.readTree(discovery.body()).path("services");
        assertEquals("payerlab-crd-prior-auth", services.get(0).path("id").asText());
        assertEquals("order-sign", services.get(0).path("hook").asText());

        HttpResponse<String> crd = postJson("/cds-services/payerlab-crd-prior-auth", """
                {
                  "hook": "order-sign",
                  "hookInstance": "workflow-hook-001",
                  "fhirServer": "http://localhost/fhir",
                  "context": {
                    "patientId": "workflow-patient",
                    "draftOrders": {
                      "resourceType": "Bundle",
                      "type": "collection"
                    }
                  }
                }
                """);
        assertEquals(200, crd.statusCode());
        JsonNode card = objectMapper.readTree(crd.body()).path("cards").get(0);
        assertEquals("warning", card.path("indicator").asText());
        assertEquals("smart", card.path("links").get(0).path("type").asText());

        HttpResponse<String> questionnaireResponse = getFhir("/fhir/Questionnaire/payerlab-dental-orthodontics");
        assertEquals(200, questionnaireResponse.statusCode());
        Questionnaire questionnaire = (Questionnaire) fhirContext.newJsonParser().parseResource(questionnaireResponse.body());
        assertEquals("payerlab-dental-orthodontics", questionnaire.getIdElement().getIdPart());
        assertEquals(3, questionnaire.getItem().size());

        HttpResponse<String> dtrSubmit = postFhir(
                "/fhir/QuestionnaireResponse/$submit",
                TestBundles.workflowQuestionnaireResponse());
        assertEquals(200, dtrSubmit.statusCode());
        assertInstanceOf(OperationOutcome.class, fhirContext.newJsonParser().parseResource(dtrSubmit.body()));

        HttpResponse<String> pasSubmit = postFhir("/fhir/Claim/$submit", TestBundles.workflowPasSubmissionBundle());
        assertEquals(200, pasSubmit.statusCode());
        ClaimResponse submitClaimResponse = firstClaimResponse(pasSubmit.body());
        assertEquals(ClaimResponse.RemittanceOutcome.QUEUED, submitClaimResponse.getOutcome());
        assertEquals("DENTAL-PA-WORKFLOW-2026-0001", submitClaimResponse.getPreAuthRef());

        HttpResponse<String> pasInquiry = postInquiry(TestBundles.workflowPasInquiryBundle(submitClaimResponse.getPreAuthRef()));
        assertEquals(200, pasInquiry.statusCode());
        ClaimResponse inquiryClaimResponse = firstClaimResponse(pasInquiry.body());
        assertEquals(ClaimResponse.RemittanceOutcome.COMPLETE, inquiryClaimResponse.getOutcome());
        assertEquals("approved", inquiryClaimResponse.getMeta().getTagFirstRep().getCode());
        assertEquals(submitClaimResponse.getPreAuthRef(), inquiryClaimResponse.getPreAuthRef());
    }

    @Test
    void exposesSmartBackendDiscoveryMetadata() throws Exception {
        HttpResponse<String> smartConfiguration = getPublicJson("/fhir/.well-known/smart-configuration");
        assertEquals(200, smartConfiguration.statusCode());
        JsonNode smart = objectMapper.readTree(smartConfiguration.body());
        assertEquals(baseUrl + "/auth", smart.path("issuer").asText());
        assertEquals(baseUrl + "/auth/token", smart.path("token_endpoint").asText());
        assertEquals(baseUrl + "/auth/jwks.json", smart.path("jwks_uri").asText());
        assertTrue(smart.path("grant_types_supported").toString().contains("client_credentials"));
        assertTrue(smart.path("token_endpoint_auth_methods_supported").toString().contains("private_key_jwt"));

        HttpResponse<String> capabilityStatement = getPublicFhir("/fhir/metadata");
        assertEquals(200, capabilityStatement.statusCode());
        assertEquals("CapabilityStatement", objectMapper.readTree(capabilityStatement.body()).path("resourceType").asText());
    }

    @Test
    void issuesBackendServicesAccessTokenForValidClientAssertion() throws Exception {
        HttpResponse<String> tokenResponse = requestToken("system/Claim.rs system/ClaimResponse.rs");
        assertEquals(200, tokenResponse.statusCode());

        JsonNode token = objectMapper.readTree(tokenResponse.body());
        assertEquals("Bearer", token.path("token_type").asText());
        assertEquals(300, token.path("expires_in").asInt());
        assertTrue(token.path("access_token").asText().split("\\.").length == 3);
    }

    @Test
    void rejectsProtectedEndpointWithoutBearerToken() throws Exception {
        HttpResponse<String> response = getPublicJson("/cds-services");

        assertEquals(401, response.statusCode());
        assertEquals("Bearer error=\"invalid_token\"", response.headers().firstValue("WWW-Authenticate").orElseThrow());
        assertInstanceOf(OperationOutcome.class, fhirContext.newJsonParser().parseResource(response.body()));
    }

    @Test
    void rejectsProtectedEndpointWithInsufficientScope() throws Exception {
        String token = accessToken("system/Claim.rs");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/fhir/Claim/$inquire"))
                .header("Content-Type", "application/fhir+json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(fhirContext.newJsonParser()
                        .encodeResourceToString(TestBundles.workflowPasInquiryBundle("DENTAL-PA-WORKFLOW-2026-0001"))))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(403, response.statusCode());
        assertInstanceOf(OperationOutcome.class, fhirContext.newJsonParser().parseResource(response.body()));
    }

    @Test
    void rejectsReplayedClientAssertion() throws Exception {
        String assertion = ClientAssertionGenerator.generate(
                Path.of("fixtures/auth/client-private.jwk.json"),
                baseUrl + "/auth/token",
                AuthService.CLIENT_ID,
                java.time.Clock.systemUTC());

        assertEquals(200, requestTokenWithAssertion("system/Claim.rs", assertion).statusCode());
        HttpResponse<String> replay = requestTokenWithAssertion("system/Claim.rs", assertion);

        assertEquals(401, replay.statusCode());
        assertEquals("invalid_client", objectMapper.readTree(replay.body()).path("error").asText());
    }

    @AfterEach
    void stopServer() throws Exception {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void postsInquiryAndReturnsApprovedBundle() throws Exception {
        HttpResponse<String> response = postInquiry(TestBundles.inquiryBundle(
                "CP-MEMBER-APPROVED",
                "CP-PAYER-001",
                "1234567893",
                "AUTH-APPROVED-001"));

        assertEquals(200, response.statusCode());
        Bundle bundle = (Bundle) fhirContext.newJsonParser().parseResource(response.body());
        ClaimResponse claimResponse = (ClaimResponse) bundle.getEntryFirstRep().getResource();

        assertEquals(ClaimResponse.RemittanceOutcome.COMPLETE, claimResponse.getOutcome());
        assertEquals("PA-APPROVED-2026-0001", claimResponse.getPreAuthRef());
    }

    @Test
    void returnsSpecificDeniedReason() throws Exception {
        HttpResponse<String> response = postInquiry(TestBundles.inquiryBundle(
                "CP-MEMBER-DENIED",
                "CP-PAYER-001",
                "1234567893",
                "AUTH-DENIED-001"));

        assertEquals(200, response.statusCode());
        Bundle bundle = (Bundle) fhirContext.newJsonParser().parseResource(response.body());
        ClaimResponse claimResponse = (ClaimResponse) bundle.getEntryFirstRep().getResource();

        assertEquals("not-medically-necessary", claimResponse.getErrorFirstRep().getCode().getCodingFirstRep().getCode());
    }

    @Test
    void returnsPendedStatus() throws Exception {
        HttpResponse<String> response = postInquiry(TestBundles.inquiryBundle(
                "CP-MEMBER-PENDED",
                "CP-PAYER-001",
                "1234567893",
                "AUTH-PENDED-001"));

        assertEquals(200, response.statusCode());
        Bundle bundle = (Bundle) fhirContext.newJsonParser().parseResource(response.body());
        ClaimResponse claimResponse = (ClaimResponse) bundle.getEntryFirstRep().getResource();

        assertEquals(ClaimResponse.RemittanceOutcome.QUEUED, claimResponse.getOutcome());
        assertEquals("pended", claimResponse.getMeta().getTagFirstRep().getCode());
    }

    @Test
    void returnsAdditionalInfoNeededStatus() throws Exception {
        HttpResponse<String> response = postInquiry(TestBundles.inquiryBundle(
                "CP-MEMBER-INFO",
                "CP-PAYER-001",
                "1234567893",
                "AUTH-INFO-001"));

        assertEquals(200, response.statusCode());
        Bundle bundle = (Bundle) fhirContext.newJsonParser().parseResource(response.body());
        ClaimResponse claimResponse = (ClaimResponse) bundle.getEntryFirstRep().getResource();

        assertEquals(ClaimResponse.RemittanceOutcome.PARTIAL, claimResponse.getOutcome());
        assertEquals("additional-info-needed", claimResponse.getMeta().getTagFirstRep().getCode());
        assertEquals(
                "Please provide current imaging report and six weeks of conservative therapy notes.",
                claimResponse.getProcessNoteFirstRep().getText());
    }

    @Test
    void returnsDentalApprovedStatusFromOralClaim() throws Exception {
        Bundle requestBundle = TestBundles.dentalInquiryBundle(
                "CP-DENTAL-MEMBER-APPROVED",
                "CP-DENTAL-PAYER-001",
                "1888888888",
                "DENTAL-AUTH-APPROVED-001");

        assertEquals(
                "oral",
                requestBundle.getEntry().stream()
                        .map(Bundle.BundleEntryComponent::getResource)
                        .filter(org.hl7.fhir.r4.model.Claim.class::isInstance)
                        .map(org.hl7.fhir.r4.model.Claim.class::cast)
                        .findFirst()
                        .orElseThrow()
                        .getType()
                        .getCodingFirstRep()
                        .getCode());

        HttpResponse<String> response = postInquiry(requestBundle);

        assertEquals(200, response.statusCode());
        ClaimResponse claimResponse = firstClaimResponse(response.body());

        assertEquals(ClaimResponse.RemittanceOutcome.COMPLETE, claimResponse.getOutcome());
        assertEquals("approved", claimResponse.getMeta().getTagFirstRep().getCode());
        assertEquals("DENTAL-PA-APPROVED-2026-0001", claimResponse.getPreAuthRef());
    }

    @Test
    void returnsDentalDeniedStatus() throws Exception {
        HttpResponse<String> response = postInquiry(TestBundles.dentalInquiryBundle(
                "CP-DENTAL-MEMBER-DENIED",
                "CP-DENTAL-PAYER-001",
                "1888888888",
                "DENTAL-AUTH-DENIED-001"));

        assertEquals(200, response.statusCode());
        ClaimResponse claimResponse = firstClaimResponse(response.body());

        assertEquals("denied", claimResponse.getMeta().getTagFirstRep().getCode());
        assertEquals("dental-benefit-not-covered", claimResponse.getErrorFirstRep().getCode().getCodingFirstRep().getCode());
    }

    @Test
    void returnsDentalPendedStatus() throws Exception {
        HttpResponse<String> response = postInquiry(TestBundles.dentalInquiryBundle(
                "CP-DENTAL-MEMBER-PENDED",
                "CP-DENTAL-PAYER-001",
                "1888888888",
                "DENTAL-AUTH-PENDED-001"));

        assertEquals(200, response.statusCode());
        ClaimResponse claimResponse = firstClaimResponse(response.body());

        assertEquals(ClaimResponse.RemittanceOutcome.QUEUED, claimResponse.getOutcome());
        assertEquals("pended", claimResponse.getMeta().getTagFirstRep().getCode());
    }

    @Test
    void returnsDentalAdditionalInfoNeededStatus() throws Exception {
        HttpResponse<String> response = postInquiry(TestBundles.dentalInquiryBundle(
                "CP-DENTAL-MEMBER-INFO",
                "CP-DENTAL-PAYER-001",
                "1888888888",
                "DENTAL-AUTH-INFO-001"));

        assertEquals(200, response.statusCode());
        ClaimResponse claimResponse = firstClaimResponse(response.body());

        assertEquals(ClaimResponse.RemittanceOutcome.PARTIAL, claimResponse.getOutcome());
        assertEquals("additional-info-needed", claimResponse.getMeta().getTagFirstRep().getCode());
        assertEquals(
                "Please provide current radiograph and periodontal charting for dental review.",
                claimResponse.getProcessNoteFirstRep().getText());
    }

    @Test
    void returnsNotFoundBundleForUnknownMatchKey() throws Exception {
        HttpResponse<String> response = postInquiry(TestBundles.inquiryBundle(
                "CP-MEMBER-UNKNOWN",
                "CP-PAYER-001",
                "1234567893",
                "AUTH-UNKNOWN-001"));

        assertEquals(200, response.statusCode());
        Bundle bundle = (Bundle) fhirContext.newJsonParser().parseResource(response.body());
        ClaimResponse claimResponse = (ClaimResponse) bundle.getEntryFirstRep().getResource();

        assertEquals(ClaimResponse.RemittanceOutcome.ERROR, claimResponse.getOutcome());
        assertEquals("not-found", claimResponse.getMeta().getTagFirstRep().getCode());
    }

    @Test
    void returnsOperationOutcomeForMalformedJson() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/fhir/Claim/$inquire"))
                .header("Content-Type", "application/fhir+json")
                .header("Authorization", "Bearer " + accessToken(ALL_SCOPES))
                .POST(HttpRequest.BodyPublishers.ofString("{not-json"))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(400, response.statusCode());
        assertInstanceOf(OperationOutcome.class, fhirContext.newJsonParser().parseResource(response.body()));
    }

    @Test
    void rejectsEndpointsOutsideClaimInquire() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/fhir/Patient/123"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(404, response.statusCode());
        assertInstanceOf(OperationOutcome.class, fhirContext.newJsonParser().parseResource(response.body()));
    }

    private HttpResponse<String> postInquiry(Bundle requestBundle) throws Exception {
        return postFhir("/fhir/Claim/$inquire", requestBundle);
    }

    private HttpResponse<String> getJson(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + accessToken(ALL_SCOPES))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getPublicJson(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Accept", "application/json")
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJson(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + accessToken(ALL_SCOPES))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getFhir(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Accept", "application/fhir+json")
                .header("Authorization", "Bearer " + accessToken(ALL_SCOPES))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getPublicFhir(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Accept", "application/fhir+json")
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postFhir(String path, IBaseResource requestResource) throws Exception {
        String body = fhirContext.newJsonParser().encodeResourceToString(requestResource);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/fhir+json")
                .header("Authorization", "Bearer " + accessToken(ALL_SCOPES))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String accessToken(String scopes) throws Exception {
        HttpResponse<String> response = requestToken(scopes);
        assertEquals(200, response.statusCode());
        return objectMapper.readTree(response.body()).path("access_token").asText();
    }

    private HttpResponse<String> requestToken(String scopes) throws Exception {
        String assertion = ClientAssertionGenerator.generate(
                Path.of("fixtures/auth/client-private.jwk.json"),
                baseUrl + "/auth/token",
                AuthService.CLIENT_ID,
                java.time.Clock.systemUTC());
        return requestTokenWithAssertion(scopes, assertion);
    }

    private HttpResponse<String> requestTokenWithAssertion(String scopes, String assertion) throws Exception {
        String body = "grant_type=client_credentials"
                + "&scope=" + encode(scopes)
                + "&client_assertion_type=" + encode(AuthService.CLIENT_ASSERTION_TYPE)
                + "&client_assertion=" + encode(assertion);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/auth/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private ClaimResponse firstClaimResponse(String responseBody) {
        Bundle bundle = (Bundle) fhirContext.newJsonParser().parseResource(responseBody);
        return (ClaimResponse) bundle.getEntryFirstRep().getResource();
    }
}
