package com.careparse.payerlab;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.QuestionnaireResponse;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;

import java.util.Date;

final class TestBundles {
    private TestBundles() {
    }

    static Bundle inquiryBundle(String memberIdentifier, String payerIdentifier, String providerNpi, String authorizationIdentifier) {
        return inquiryBundle(
                memberIdentifier,
                payerIdentifier,
                providerNpi,
                authorizationIdentifier,
                "professional",
                "Professional");
    }

    static Bundle dentalInquiryBundle(
            String memberIdentifier,
            String payerIdentifier,
            String providerNpi,
            String authorizationIdentifier) {
        return inquiryBundle(
                memberIdentifier,
                payerIdentifier,
                providerNpi,
                authorizationIdentifier,
                "oral",
                "Oral");
    }

    static QuestionnaireResponse workflowQuestionnaireResponse() {
        QuestionnaireResponse response = new QuestionnaireResponse();
        response.setId("workflow-dtr-response");
        response.setQuestionnaire("https://careparse.com/fhir/payerlab/Questionnaire/payerlab-dental-orthodontics");
        response.setStatus(QuestionnaireResponse.QuestionnaireResponseStatus.COMPLETED);
        response.setSubject(new Reference("Patient/workflow-patient"));
        response.addItem()
                .setLinkId("radiograph-date")
                .setText("Most recent radiograph date")
                .addAnswer()
                .setValue(new DateType("2026-01-02"));
        response.addItem()
                .setLinkId("periodontal-charting")
                .setText("Periodontal charting summary")
                .addAnswer()
                .setValue(new StringType("Generalized mild periodontal findings with no active infection."));
        response.addItem()
                .setLinkId("medical-necessity")
                .setText("Clinical rationale for orthodontic evaluation")
                .addAnswer()
                .setValue(new StringType("Functional malocclusion evaluation requested after dental examination."));
        return response;
    }

    static Bundle workflowPasSubmissionBundle() {
        return dentalInquiryBundle(
                "CP-DENTAL-MEMBER-WORKFLOW",
                "CP-DENTAL-PAYER-001",
                "1888888888",
                "ORDER-DENTAL-ORTHO-001");
    }

    static Bundle workflowPasInquiryBundle(String authorizationIdentifier) {
        return dentalInquiryBundle(
                "CP-DENTAL-MEMBER-WORKFLOW",
                "CP-DENTAL-PAYER-001",
                "1888888888",
                authorizationIdentifier);
    }

    private static Bundle inquiryBundle(
            String memberIdentifier,
            String payerIdentifier,
            String providerNpi,
            String authorizationIdentifier,
            String claimTypeCode,
            String claimTypeDisplay) {
        Patient patient = new Patient();
        patient.setId("Patient/inquiry-patient");
        patient.addIdentifier()
                .setSystem("https://careparse.com/fhir/payerlab/member")
                .setValue(memberIdentifier);

        Organization payer = new Organization();
        payer.setId("Organization/inquiry-payer");
        payer.addIdentifier()
                .setSystem("https://careparse.com/fhir/payerlab/payer")
                .setValue(payerIdentifier);

        Practitioner provider = new Practitioner();
        provider.setId("Practitioner/inquiry-provider");
        provider.addIdentifier()
                .setSystem("http://hl7.org/fhir/sid/us-npi")
                .setValue(providerNpi);

        Coverage coverage = new Coverage();
        coverage.setId("Coverage/inquiry-coverage");
        coverage.setSubscriberId(memberIdentifier);
        coverage.setBeneficiary(new Reference("urn:uuid:patient"));
        coverage.setPayor(java.util.List.of(new Reference("urn:uuid:payer")));

        Claim claim = new Claim();
        claim.setId("Claim/inquiry-claim");
        claim.addIdentifier()
                .setSystem("https://careparse.com/fhir/payerlab/authorization")
                .setValue(authorizationIdentifier);
        claim.setStatus(Claim.ClaimStatus.ACTIVE);
        claim.setType(new CodeableConcept(new Coding(
                "http://terminology.hl7.org/CodeSystem/claim-type",
                claimTypeCode,
                claimTypeDisplay)));
        claim.setUse(Claim.Use.PREAUTHORIZATION);
        claim.setPatient(new Reference("urn:uuid:patient"));
        claim.setCreated(new Date(1767225600000L));
        claim.setInsurer(new Reference("urn:uuid:payer"));
        claim.setProvider(new Reference("urn:uuid:provider"));
        claim.addInsurance()
                .setSequence(1)
                .setFocal(true)
                .setCoverage(new Reference("urn:uuid:coverage"))
                .addPreAuthRef(authorizationIdentifier);

        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.COLLECTION);
        bundle.addEntry().setFullUrl("urn:uuid:patient").setResource(patient);
        bundle.addEntry().setFullUrl("urn:uuid:payer").setResource(payer);
        bundle.addEntry().setFullUrl("urn:uuid:provider").setResource(provider);
        bundle.addEntry().setFullUrl("urn:uuid:coverage").setResource(coverage);
        bundle.addEntry().setFullUrl("urn:uuid:claim").setResource(claim);
        return bundle;
    }

    static Bundle inquiryBundleWithOrganizationProvider(
            String memberIdentifier,
            String payerIdentifier,
            String providerNpi,
            String authorizationIdentifier) {
        Bundle bundle = inquiryBundle(memberIdentifier, payerIdentifier, "0000000000", authorizationIdentifier);

        Organization provider = new Organization();
        provider.setId("Organization/inquiry-provider-organization");
        provider.addIdentifier()
                .setSystem("http://hl7.org/fhir/sid/us-npi")
                .setValue(providerNpi);

        bundle.addEntry().setFullUrl("urn:uuid:provider-organization").setResource(provider);
        bundle.getEntry().stream()
                .map(Bundle.BundleEntryComponent::getResource)
                .filter(Claim.class::isInstance)
                .map(Claim.class::cast)
                .findFirst()
                .orElseThrow()
                .setProvider(new Reference("urn:uuid:provider-organization"));

        return bundle;
    }
}
