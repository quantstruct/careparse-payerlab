package com.careparse.payerlab;

import ca.uhn.fhir.context.FhirContext;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.ClaimResponse;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

final class PayerLabServerHttpTest {
    private final FhirContext fhirContext = FhirContext.forR4();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private Server server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws Exception {
        server = PayerLabServer.createServer(0, Path.of("fixtures/payer"));
        server.start();
        int port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
        baseUrl = "http://localhost:" + port;
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
                .POST(HttpRequest.BodyPublishers.ofString("{not-json"))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(400, response.statusCode());
        assertInstanceOf(OperationOutcome.class, fhirContext.newJsonParser().parseResource(response.body()));
    }

    @Test
    void rejectsEndpointsOutsideClaimInquire() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/fhir/metadata"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(404, response.statusCode());
        assertInstanceOf(OperationOutcome.class, fhirContext.newJsonParser().parseResource(response.body()));
    }

    private HttpResponse<String> postInquiry(Bundle requestBundle) throws Exception {
        String body = fhirContext.newJsonParser().encodeResourceToString(requestBundle);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/fhir/Claim/$inquire"))
                .header("Content-Type", "application/fhir+json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
