package com.careparse.payerlab;

import ca.uhn.fhir.context.FhirContext;
import jakarta.servlet.http.HttpServletResponse;
import org.hl7.fhir.r4.model.OperationOutcome;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class FhirHttpResponses {
    private static final String FHIR_JSON = "application/fhir+json;charset=UTF-8";

    private FhirHttpResponses() {
    }

    public static void writeRawResource(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(FHIR_JSON);
        response.getWriter().write(body);
    }

    public static void writeOperationOutcome(
            FhirContext fhirContext,
            HttpServletResponse response,
            int status,
            String issueCode,
            String diagnostics) throws IOException {
        OperationOutcome outcome = new OperationOutcome();
        OperationOutcome.IssueType type = toIssueType(issueCode);
        outcome.addIssue()
                .setSeverity(OperationOutcome.IssueSeverity.ERROR)
                .setCode(type)
                .setDiagnostics(diagnostics);

        writeRawResource(response, status, fhirContext.newJsonParser().encodeResourceToString(outcome));
    }

    private static OperationOutcome.IssueType toIssueType(String issueCode) {
        return switch (issueCode) {
            case "not-supported" -> OperationOutcome.IssueType.NOTSUPPORTED;
            case "not-found" -> OperationOutcome.IssueType.NOTFOUND;
            case "required" -> OperationOutcome.IssueType.REQUIRED;
            case "exception" -> OperationOutcome.IssueType.EXCEPTION;
            default -> OperationOutcome.IssueType.INVALID;
        };
    }
}
