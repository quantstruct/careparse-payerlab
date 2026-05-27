package com.careparse.payerlab;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.server.RestfulServer;

public final class FhirServlet extends RestfulServer {
    public FhirServlet(FhirContext fhirContext, PayerFixtureRepository repository) {
        super(fhirContext);
        setDefaultPrettyPrint(true);
        registerProvider(new ClaimInquireProvider(fhirContext, repository));
    }
}
