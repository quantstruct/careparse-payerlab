package com.careparse.payerlab;

import org.hl7.fhir.r4.model.Bundle;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class InquiryMatcherTest {
    @Test
    void extractsFixtureMatchKeyFromRequestBundle() {
        Bundle bundle = TestBundles.inquiryBundle(
                "CP-MEMBER-APPROVED",
                "CP-PAYER-001",
                "1234567893",
                "AUTH-APPROVED-001");

        InquiryRequest request = InquiryMatcher.fromBundle(bundle);

        assertEquals("CP-MEMBER-APPROVED", request.patientMemberIdentifier());
        assertEquals("CP-PAYER-001", request.payerIdentifier());
        assertEquals("1234567893", request.requestingProviderNpi());
        assertEquals("AUTH-APPROVED-001", request.authorizationIdentifier());
    }

    @Test
    void resolvesProviderOrganizationBeforeUnrelatedPractitioner() {
        Bundle bundle = TestBundles.inquiryBundleWithOrganizationProvider(
                "CP-MEMBER-APPROVED",
                "CP-PAYER-001",
                "1234567893",
                "AUTH-APPROVED-001");

        InquiryRequest request = InquiryMatcher.fromBundle(bundle);

        assertEquals("1234567893", request.requestingProviderNpi());
    }

    @Test
    void requiresAClaimResource() {
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.COLLECTION);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> InquiryMatcher.fromBundle(bundle));

        assertEquals("Request Bundle must contain a Claim resource.", error.getMessage());
    }
}
