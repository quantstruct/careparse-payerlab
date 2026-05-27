package com.careparse.payerlab;

import ca.uhn.fhir.context.FhirContext;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.ClaimResponse;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PayerFixtureRepositoryTest {
    private final FhirContext fhirContext = FhirContext.forR4();
    private final PayerFixtureRepository repository = new PayerFixtureRepository(fhirContext, Path.of("fixtures/payer"));

    @Test
    void resolvesApprovedFixtureByMatchKey() {
        PayerFixtureRepository.FixtureResponse response = repository.resolve(new InquiryRequest(
                "CP-MEMBER-APPROVED",
                "CP-PAYER-001",
                "1234567893",
                "AUTH-APPROVED-001"));

        assertEquals(200, response.httpStatus());
        Bundle bundle = (Bundle) fhirContext.newJsonParser().parseResource(response.body());
        ClaimResponse claimResponse = (ClaimResponse) bundle.getEntryFirstRep().getResource();

        assertEquals(ClaimResponse.RemittanceOutcome.COMPLETE, claimResponse.getOutcome());
        assertEquals("approved", claimResponse.getMeta().getTagFirstRep().getCode());
    }

    @Test
    void returnsDefaultNotFoundBundleWhenNoFixtureMatches() {
        PayerFixtureRepository.FixtureResponse response = repository.resolve(new InquiryRequest(
                "CP-MEMBER-MISSING",
                "CP-PAYER-001",
                "1234567893",
                "AUTH-MISSING-001"));

        assertEquals(200, response.httpStatus());
        Bundle bundle = (Bundle) fhirContext.newJsonParser().parseResource(response.body());

        assertTrue(bundle.getEntry().stream()
                .map(Bundle.BundleEntryComponent::getResource)
                .filter(ClaimResponse.class::isInstance)
                .map(ClaimResponse.class::cast)
                .anyMatch(claimResponse -> "not-found".equals(claimResponse.getMeta().getTagFirstRep().getCode())));
    }

    @Test
    void resolvesTechnicalErrorFixture() {
        PayerFixtureRepository.FixtureResponse response = repository.resolve(new InquiryRequest(
                "CP-MEMBER-TECHNICAL",
                "CP-PAYER-001",
                "1234567893",
                "AUTH-TECHNICAL-001"));

        assertEquals(500, response.httpStatus());
        assertInstanceOf(
                org.hl7.fhir.r4.model.OperationOutcome.class,
                fhirContext.newJsonParser().parseResource(response.body()));
    }
}
