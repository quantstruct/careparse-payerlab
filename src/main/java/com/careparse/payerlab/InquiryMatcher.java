package com.careparse.payerlab;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.DomainResource;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.StringType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class InquiryMatcher {
    private InquiryMatcher() {
    }

    public static InquiryRequest fromBundle(Bundle bundle) {
        List<ResourceEntry> resources = bundle.getEntry().stream()
                .filter(entry -> entry.getResource() instanceof Resource)
                .map(entry -> new ResourceEntry(entry.getFullUrl(), (Resource) entry.getResource()))
                .toList();

        List<Resource> rawResources = resources.stream()
                .map(ResourceEntry::resource)
                .filter(Resource.class::isInstance)
                .map(Resource.class::cast)
                .toList();

        Claim claim = rawResources.stream()
                .filter(Claim.class::isInstance)
                .map(Claim.class::cast)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Request Bundle must contain a Claim resource."));

        String patientMemberIdentifier = findPatientMemberIdentifier(claim, resources)
                .orElseThrow(() -> new IllegalArgumentException("Request Bundle must include a patient member identifier."));
        String payerIdentifier = findPayerIdentifier(claim, resources)
                .orElseThrow(() -> new IllegalArgumentException("Request Bundle must include a payer identifier."));
        String providerNpi = findProviderNpi(claim, resources)
                .orElseThrow(() -> new IllegalArgumentException("Request Bundle must include a requesting provider NPI."));
        String authorizationIdentifier = findAuthorizationIdentifier(bundle, claim).orElse(null);

        return new InquiryRequest(patientMemberIdentifier, payerIdentifier, providerNpi, authorizationIdentifier);
    }

    private static Optional<String> findPatientMemberIdentifier(Claim claim, List<ResourceEntry> resources) {
        return resourceReferencedBy(claim.getPatient(), resources, Patient.class)
                .flatMap(patient -> firstIdentifierValue(patient.getIdentifier()))
                .or(() -> resources.stream()
                        .map(ResourceEntry::resource)
                        .filter(Patient.class::isInstance)
                        .map(Patient.class::cast)
                        .map(patient -> firstIdentifierValue(patient.getIdentifier()))
                        .flatMap(Optional::stream)
                        .findFirst())
                .or(() -> resources.stream()
                        .map(ResourceEntry::resource)
                        .filter(Coverage.class::isInstance)
                        .map(Coverage.class::cast)
                        .map(coverage -> firstNonBlank(coverage.getSubscriberId(), firstIdentifierValue(coverage.getIdentifier()).orElse(null)))
                        .flatMap(Optional::stream)
                        .findFirst());
    }

    private static Optional<String> findPayerIdentifier(Claim claim, List<ResourceEntry> resources) {
        return resourceReferencedBy(claim.getInsurer(), resources, Organization.class)
                .flatMap(organization -> firstIdentifierValue(organization.getIdentifier()))
                .or(() -> resources.stream()
                        .map(ResourceEntry::resource)
                        .filter(Organization.class::isInstance)
                        .map(Organization.class::cast)
                        .map(organization -> firstIdentifierValue(organization.getIdentifier()))
                        .flatMap(Optional::stream)
                        .findFirst());
    }

    private static Optional<String> findProviderNpi(Claim claim, List<ResourceEntry> resources) {
        return resourceReferencedBy(claim.getProvider(), resources, Practitioner.class)
                .flatMap(InquiryMatcher::npiIdentifierValue)
                .or(() -> resourceReferencedBy(claim.getProvider(), resources, Organization.class)
                        .flatMap(InquiryMatcher::npiIdentifierValue))
                .or(() -> resources.stream()
                        .map(ResourceEntry::resource)
                        .filter(Practitioner.class::isInstance)
                        .map(Practitioner.class::cast)
                        .map(InquiryMatcher::npiIdentifierValue)
                        .flatMap(Optional::stream)
                        .findFirst())
                .or(() -> resources.stream()
                        .map(ResourceEntry::resource)
                        .filter(Organization.class::isInstance)
                        .map(Organization.class::cast)
                        .map(InquiryMatcher::npiIdentifierValue)
                        .flatMap(Optional::stream)
                        .findFirst());
    }

    private static Optional<String> findAuthorizationIdentifier(Bundle bundle, Claim claim) {
        return firstNonBlank(
                claim.getInsurance().stream()
                        .flatMap(insurance -> insurance.getPreAuthRef().stream())
                        .map(StringType::getValue)
                        .filter(InquiryMatcher::hasText)
                        .findFirst()
                        .orElse(null),
                firstIdentifierValue(claim.getIdentifier()).orElse(null),
                bundle.hasIdentifier() ? bundle.getIdentifier().getValue() : null);
    }

    private static Optional<String> firstIdentifierValue(List<Identifier> identifiers) {
        return identifiers.stream()
                .map(Identifier::getValue)
                .filter(InquiryMatcher::hasText)
                .findFirst();
    }

    private static Optional<String> npiIdentifierValue(DomainResource resource) {
        List<Identifier> identifiers = new ArrayList<>();
        if (resource instanceof Practitioner practitioner) {
            identifiers.addAll(practitioner.getIdentifier());
        } else if (resource instanceof Organization organization) {
            identifiers.addAll(organization.getIdentifier());
        }

        Optional<String> explicitNpi = identifiers.stream()
                .filter(identifier -> containsNpiHint(identifier.getSystem()))
                .map(Identifier::getValue)
                .filter(InquiryMatcher::hasText)
                .findFirst();

        return explicitNpi.or(() -> identifiers.stream()
                .map(Identifier::getValue)
                .filter(value -> value != null && value.matches("\\d{10}"))
                .findFirst());
    }

    private static <T extends Resource> Optional<T> resourceReferencedBy(
            Reference reference,
            List<ResourceEntry> resources,
            Class<T> resourceType) {
        if (reference == null || !reference.hasReference()) {
            return Optional.empty();
        }

        String expected = reference.getReference();
        String expectedIdPart = expected.contains("/") ? expected.substring(expected.indexOf('/') + 1) : expected;

        return resources.stream()
                .filter(entry -> entry.fullUrl() != null && expected.equals(entry.fullUrl()))
                .map(ResourceEntry::resource)
                .filter(resourceType::isInstance)
                .map(resourceType::cast)
                .findFirst()
                .or(() -> resources.stream()
                        .map(ResourceEntry::resource)
                        .filter(resourceType::isInstance)
                        .map(resourceType::cast)
                        .filter(resource -> expected.equals(resource.getIdElement().getValue())
                                || expected.equals(resource.getIdElement().toUnqualifiedVersionless().getValue())
                                || expectedIdPart.equals(resource.getIdElement().getIdPart()))
                        .findFirst());
    }

    private static Optional<String> firstNonBlank(String... values) {
        for (String value : values) {
            if (hasText(value)) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    private static boolean containsNpiHint(String system) {
        return system != null && system.toLowerCase(Locale.ROOT).contains("npi");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record ResourceEntry(String fullUrl, Resource resource) {
    }
}
