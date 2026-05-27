package com.careparse.payerlab;

import ca.uhn.fhir.context.FhirContext;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

public final class EndpointRestrictionFilter implements Filter {
    private static final String ALLOWED_PATH = "/fhir/Claim/$inquire";

    private final FhirContext fhirContext;

    public EndpointRestrictionFilter(FhirContext fhirContext) {
        this.fhirContext = fhirContext;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        if (!ALLOWED_PATH.equals(httpRequest.getRequestURI())) {
            FhirHttpResponses.writeOperationOutcome(
                    fhirContext,
                    httpResponse,
                    HttpServletResponse.SC_NOT_FOUND,
                    "not-supported",
                    "Only POST /fhir/Claim/$inquire is exposed by this simulator.");
            return;
        }

        if (!"POST".equalsIgnoreCase(httpRequest.getMethod())) {
            httpResponse.setHeader("Allow", "POST");
            FhirHttpResponses.writeOperationOutcome(
                    fhirContext,
                    httpResponse,
                    HttpServletResponse.SC_METHOD_NOT_ALLOWED,
                    "not-supported",
                    "Only POST is supported for /fhir/Claim/$inquire.");
            return;
        }

        chain.doFilter(request, response);
    }
}
