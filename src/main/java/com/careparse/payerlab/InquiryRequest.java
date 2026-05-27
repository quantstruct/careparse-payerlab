package com.careparse.payerlab;

public record InquiryRequest(
        String patientMemberIdentifier,
        String payerIdentifier,
        String requestingProviderNpi,
        String authorizationIdentifier) {
}
