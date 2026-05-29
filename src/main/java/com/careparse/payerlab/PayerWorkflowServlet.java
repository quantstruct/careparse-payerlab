package com.careparse.payerlab;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.DataFormatException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.QuestionnaireResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PayerWorkflowServlet extends HttpServlet {
    private static final String JSON = "application/json;charset=UTF-8";
    private static final String WORKFLOW_MEMBER = "CP-DENTAL-MEMBER-WORKFLOW";
    private static final String WORKFLOW_PAYER = "CP-DENTAL-PAYER-001";
    private static final String WORKFLOW_PROVIDER_NPI = "1888888888";

    private final FhirContext fhirContext;
    private final Path fixtureRoot;
    private final ObjectMapper mapper = new ObjectMapper();

    public PayerWorkflowServlet(FhirContext fhirContext, Path fixtureRoot) {
        this.fhirContext = fhirContext;
        this.fixtureRoot = fixtureRoot;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getRequestURI();
        if ("/cds-services".equals(path)) {
            writeJsonFixture(response, "cds-services.json");
            return;
        }

        if ("/fhir/Questionnaire/payerlab-dental-orthodontics".equals(path)) {
            writeFhirFixture(response, "questionnaires/dental-orthodontics-questionnaire.json");
            return;
        }

        FhirHttpResponses.writeOperationOutcome(
                fhirContext,
                response,
                HttpServletResponse.SC_NOT_FOUND,
                "not-supported",
                "The payer simulator does not expose this workflow endpoint.");
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getRequestURI();
        if ("/cds-services/payerlab-crd-prior-auth".equals(path)) {
            if (!validateJson(request, response, "CRD hook request must be JSON.")) {
                return;
            }
            writeJsonFixture(response, "cds-services/prior-auth-required.json");
            return;
        }

        if ("/fhir/QuestionnaireResponse/$submit".equals(path)) {
            IBaseResource parsed = parseFhirRequest(request, response, "DTR submit expects a valid FHIR R4 QuestionnaireResponse.");
            if (parsed == null) {
                return;
            }
            if (!(parsed instanceof QuestionnaireResponse)) {
                FhirHttpResponses.writeOperationOutcome(
                        fhirContext,
                        response,
                        HttpServletResponse.SC_BAD_REQUEST,
                        "invalid",
                        "DTR submit expects a FHIR R4 QuestionnaireResponse request body.");
                return;
            }
            writeDtrAccepted(response);
            return;
        }

        if ("/fhir/Claim/$submit".equals(path)) {
            IBaseResource parsed = parseFhirRequest(request, response, "Claim $submit expects a valid FHIR R4 Bundle.");
            if (parsed == null) {
                return;
            }
            if (!(parsed instanceof Bundle requestBundle)) {
                FhirHttpResponses.writeOperationOutcome(
                        fhirContext,
                        response,
                        HttpServletResponse.SC_BAD_REQUEST,
                        "invalid",
                        "Claim $submit expects a FHIR R4 Bundle request body.");
                return;
            }

            InquiryRequest requestKey;
            try {
                requestKey = InquiryMatcher.fromBundle(requestBundle);
            } catch (IllegalArgumentException e) {
                FhirHttpResponses.writeOperationOutcome(
                        fhirContext,
                        response,
                        HttpServletResponse.SC_BAD_REQUEST,
                        "required",
                        e.getMessage());
                return;
            }

            if (isWorkflowSubmission(requestKey)) {
                writeFhirFixture(response, "responses/dental-workflow-submit.bundle.json");
                return;
            }

            writeFhirFixture(response, "responses/not-found.bundle.json");
            return;
        }

        FhirHttpResponses.writeOperationOutcome(
                fhirContext,
                response,
                HttpServletResponse.SC_NOT_FOUND,
                "not-supported",
                "The payer simulator does not expose this workflow endpoint.");
    }

    private void writeJsonFixture(HttpServletResponse response, String fixturePath) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(JSON);
        response.getWriter().write(Files.readString(fixtureRoot.resolve(fixturePath), StandardCharsets.UTF_8));
    }

    private void writeFhirFixture(HttpServletResponse response, String fixturePath) throws IOException {
        FhirHttpResponses.writeRawResource(
                response,
                HttpServletResponse.SC_OK,
                Files.readString(fixtureRoot.resolve(fixturePath), StandardCharsets.UTF_8));
    }

    private boolean validateJson(HttpServletRequest request, HttpServletResponse response, String diagnostics) throws IOException {
        try {
            mapper.readTree(request.getInputStream());
            return true;
        } catch (IOException e) {
            FhirHttpResponses.writeOperationOutcome(
                    fhirContext,
                    response,
                    HttpServletResponse.SC_BAD_REQUEST,
                    "invalid",
                    diagnostics);
            return false;
        }
    }

    private IBaseResource parseFhirRequest(
            HttpServletRequest request,
            HttpServletResponse response,
            String diagnostics) throws IOException {
        byte[] requestBytes = request.getInputStream().readAllBytes();
        try {
            return fhirContext.newJsonParser().parseResource(new String(requestBytes, StandardCharsets.UTF_8));
        } catch (DataFormatException e) {
            FhirHttpResponses.writeOperationOutcome(
                    fhirContext,
                    response,
                    HttpServletResponse.SC_BAD_REQUEST,
                    "invalid",
                    diagnostics);
            return null;
        }
    }

    private void writeDtrAccepted(HttpServletResponse response) throws IOException {
        OperationOutcome outcome = new OperationOutcome();
        outcome.addIssue()
                .setSeverity(OperationOutcome.IssueSeverity.INFORMATION)
                .setCode(OperationOutcome.IssueType.INFORMATIONAL)
                .setDiagnostics("QuestionnaireResponse accepted for PAS supporting documentation.");
        FhirHttpResponses.writeRawResource(
                response,
                HttpServletResponse.SC_OK,
                fhirContext.newJsonParser().encodeResourceToString(outcome));
    }

    private static boolean isWorkflowSubmission(InquiryRequest request) {
        return WORKFLOW_MEMBER.equals(request.patientMemberIdentifier())
                && WORKFLOW_PAYER.equals(request.payerIdentifier())
                && WORKFLOW_PROVIDER_NPI.equals(request.requestingProviderNpi());
    }
}
