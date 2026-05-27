package com.careparse.payerlab;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Claim;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class ClaimInquireProvider {
    private final FhirContext fhirContext;
    private final PayerFixtureRepository repository;

    public ClaimInquireProvider(FhirContext fhirContext, PayerFixtureRepository repository) {
        this.fhirContext = fhirContext;
        this.repository = repository;
    }

    @Operation(name = "$inquire", type = Claim.class, manualRequest = true, manualResponse = true)
    public void inquire(
            HttpServletRequest servletRequest,
            RequestDetails requestDetails,
            HttpServletResponse servletResponse) throws IOException {
        byte[] requestBytes = requestDetails.getInputStream().readAllBytes();
        IBaseResource parsed;

        try {
            parsed = fhirContext.newJsonParser().parseResource(new String(requestBytes, StandardCharsets.UTF_8));
        } catch (DataFormatException e) {
            FhirHttpResponses.writeOperationOutcome(
                    fhirContext,
                    servletResponse,
                    HttpServletResponse.SC_BAD_REQUEST,
                    "invalid",
                    "Request body must be a valid FHIR R4 JSON Bundle.");
            return;
        }

        if (!(parsed instanceof Bundle requestBundle)) {
            FhirHttpResponses.writeOperationOutcome(
                    fhirContext,
                    servletResponse,
                    HttpServletResponse.SC_BAD_REQUEST,
                    "invalid",
                    "Claim $inquire expects a FHIR R4 Bundle request body.");
            return;
        }

        InquiryRequest inquiryRequest;
        try {
            inquiryRequest = InquiryMatcher.fromBundle(requestBundle);
        } catch (IllegalArgumentException e) {
            FhirHttpResponses.writeOperationOutcome(
                    fhirContext,
                    servletResponse,
                    HttpServletResponse.SC_BAD_REQUEST,
                    "required",
                    e.getMessage());
            return;
        }

        PayerFixtureRepository.FixtureResponse fixtureResponse = repository.resolve(inquiryRequest);
        FhirHttpResponses.writeRawResource(servletResponse, fixtureResponse.httpStatus(), fixtureResponse.body());
    }
}
