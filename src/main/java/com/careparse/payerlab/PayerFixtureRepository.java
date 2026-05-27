package com.careparse.payerlab;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.DataFormatException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public final class PayerFixtureRepository {
    private static final String DEFAULT_NOT_FOUND_RESPONSE = "responses/not-found.bundle.json";

    private final List<FixtureCase> cases;
    private final FixtureResponse notFoundResponse;

    public PayerFixtureRepository(FhirContext fhirContext, Path fixtureRoot) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            this.cases = mapper.readValue(
                    Files.readString(fixtureRoot.resolve("cases.json"), StandardCharsets.UTF_8),
                    new TypeReference<>() {
                    });
            this.notFoundResponse = loadResponse(fhirContext, fixtureRoot, DEFAULT_NOT_FOUND_RESPONSE, 200);
            for (FixtureCase fixtureCase : cases) {
                fixtureCase.loadedResponse = loadResponse(
                        fhirContext,
                        fixtureRoot,
                        fixtureCase.response(),
                        fixtureCase.httpStatus());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load payer simulator fixtures from " + fixtureRoot, e);
        }
    }

    public FixtureResponse resolve(InquiryRequest request) {
        return cases.stream()
                .filter(fixtureCase -> fixtureCase.matches(request))
                .findFirst()
                .map(fixtureCase -> fixtureCase.loadedResponse)
                .orElse(notFoundResponse);
    }

    private static FixtureResponse loadResponse(
            FhirContext fhirContext,
            Path fixtureRoot,
            String responsePath,
            int httpStatus) throws IOException {
        String body = Files.readString(fixtureRoot.resolve(responsePath), StandardCharsets.UTF_8);
        try {
            Resource parsed = (Resource) fhirContext.newJsonParser().parseResource(body);
            if (!(parsed instanceof Bundle) && !(parsed instanceof OperationOutcome)) {
                throw new IllegalStateException("Fixture response must be a Bundle or OperationOutcome: " + responsePath);
            }
        } catch (DataFormatException e) {
            throw new IllegalStateException("Fixture response is not valid FHIR JSON: " + responsePath, e);
        }
        return new FixtureResponse(httpStatus, body);
    }

    public record FixtureResponse(int httpStatus, String body) {
    }

    public static final class FixtureCase {
        public String state;
        public String patientMemberIdentifier;
        public String payerIdentifier;
        public String requestingProviderNpi;
        public String authorizationIdentifier;
        public int httpStatus;
        public String response;
        public transient FixtureResponse loadedResponse;

        public String state() {
            return state;
        }

        public String response() {
            return response;
        }

        public int httpStatus() {
            return httpStatus == 0 ? 200 : httpStatus;
        }

        boolean matches(InquiryRequest request) {
            if (!Objects.equals(patientMemberIdentifier, request.patientMemberIdentifier())
                    || !Objects.equals(payerIdentifier, request.payerIdentifier())
                    || !Objects.equals(requestingProviderNpi, request.requestingProviderNpi())) {
                return false;
            }

            if (request.authorizationIdentifier() == null || request.authorizationIdentifier().isBlank()) {
                return true;
            }

            return Objects.equals(authorizationIdentifier, request.authorizationIdentifier());
        }
    }
}
