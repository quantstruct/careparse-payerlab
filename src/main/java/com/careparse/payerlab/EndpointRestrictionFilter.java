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
import java.util.Set;

public final class EndpointRestrictionFilter implements Filter {
    private static final String CLAIM_INQUIRE_PATH = "/fhir/Claim/$inquire";
    private static final String CLAIM_SUBMIT_PATH = "/fhir/Claim/$submit";
    private static final String QUESTIONNAIRE_PATH = "/fhir/Questionnaire/payerlab-dental-orthodontics";
    private static final String QUESTIONNAIRE_RESPONSE_SUBMIT_PATH = "/fhir/QuestionnaireResponse/$submit";
    private static final String CDS_SERVICES_PATH = "/cds-services";
    private static final String CDS_PRIOR_AUTH_PATH = "/cds-services/payerlab-crd-prior-auth";
    private static final String SMART_CONFIGURATION_PATH = "/fhir/.well-known/smart-configuration";
    private static final String OPENID_CONFIGURATION_PATH = "/auth/.well-known/openid-configuration";
    private static final String JWKS_PATH = "/auth/jwks.json";
    private static final String TOKEN_PATH = "/auth/token";
    private static final String CAPABILITY_STATEMENT_PATH = "/fhir/metadata";

    private final FhirContext fhirContext;
    private final AuthService authService;

    public EndpointRestrictionFilter(FhirContext fhirContext, AuthService authService) {
        this.fhirContext = fhirContext;
        this.authService = authService;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        String requestUri = httpRequest.getRequestURI();
        String requiredMethod = requiredMethodFor(requestUri);

        if (requiredMethod == null) {
            FhirHttpResponses.writeOperationOutcome(
                    fhirContext,
                    httpResponse,
                    HttpServletResponse.SC_NOT_FOUND,
                    "not-supported",
                    "Only the CRD/DTR/PAS payer simulator endpoints are exposed.");
            return;
        }

        if (!requiredMethod.equalsIgnoreCase(httpRequest.getMethod())) {
            httpResponse.setHeader("Allow", requiredMethod);
            FhirHttpResponses.writeOperationOutcome(
                    fhirContext,
                    httpResponse,
                    HttpServletResponse.SC_METHOD_NOT_ALLOWED,
                    "not-supported",
                    "This endpoint only supports " + requiredMethod + ".");
            return;
        }

        Set<String> requiredScopes = requiredScopesFor(requestUri);
        if (!requiredScopes.isEmpty()) {
            AuthService.AccessDecision decision = authService.authorize(httpRequest, requiredScopes);
            if (!decision.allowed()) {
                if (decision.status() == HttpServletResponse.SC_UNAUTHORIZED) {
                    httpResponse.setHeader("WWW-Authenticate", "Bearer error=\"" + decision.error() + "\"");
                }
                FhirHttpResponses.writeOperationOutcome(
                        fhirContext,
                        httpResponse,
                        decision.status(),
                        decision.error(),
                        decision.diagnostics());
                return;
            }
        }

        chain.doFilter(request, response);
    }

    private static String requiredMethodFor(String requestUri) {
        return switch (requestUri) {
            case CDS_SERVICES_PATH, QUESTIONNAIRE_PATH, SMART_CONFIGURATION_PATH, OPENID_CONFIGURATION_PATH,
                 JWKS_PATH, CAPABILITY_STATEMENT_PATH -> "GET";
            case CDS_PRIOR_AUTH_PATH, TOKEN_PATH -> "POST";
            case CLAIM_INQUIRE_PATH, CLAIM_SUBMIT_PATH, QUESTIONNAIRE_RESPONSE_SUBMIT_PATH -> "POST";
            default -> null;
        };
    }

    private static Set<String> requiredScopesFor(String requestUri) {
        return switch (requestUri) {
            case CDS_SERVICES_PATH, CDS_PRIOR_AUTH_PATH -> Set.of("system/Coverage.rs");
            case QUESTIONNAIRE_PATH -> Set.of("system/Questionnaire.rs");
            case QUESTIONNAIRE_RESPONSE_SUBMIT_PATH -> Set.of("system/QuestionnaireResponse.c");
            case CLAIM_SUBMIT_PATH -> Set.of("system/Claim.c");
            case CLAIM_INQUIRE_PATH -> Set.of("system/Claim.rs", "system/ClaimResponse.rs");
            default -> Set.of();
        };
    }
}
