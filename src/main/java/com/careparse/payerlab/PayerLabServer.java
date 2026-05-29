package com.careparse.payerlab;

import ca.uhn.fhir.context.FhirContext;
import jakarta.servlet.DispatcherType;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;

import java.nio.file.Path;
import java.util.EnumSet;

public final class PayerLabServer {
    private PayerLabServer() {
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getProperty("payerlab.port", System.getenv().getOrDefault("PAYERLAB_PORT", "8080")));
        Path fixtureRoot = Path.of(System.getProperty("payerlab.fixtures.dir", "fixtures/payer"));
        Server server = createServer(port, fixtureRoot);
        server.start();
        System.out.printf("CareParse PayerLab listening at http://localhost:%d/fhir/Claim/$inquire%n", port);
        server.join();
    }

    public static Server createServer(int port, Path fixtureRoot) {
        FhirContext fhirContext = FhirContext.forR4();
        PayerFixtureRepository repository = new PayerFixtureRepository(fhirContext, fixtureRoot);
        Path authRoot = fixtureRoot.getParent() == null ? Path.of("fixtures/auth") : fixtureRoot.getParent().resolve("auth");
        AuthService authService = new AuthService(authRoot);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        context.setContextPath("/");
        context.addFilter(
                new FilterHolder(new EndpointRestrictionFilter(fhirContext, authService)),
                "/*",
                EnumSet.of(DispatcherType.REQUEST));
        context.addServlet(new ServletHolder(new AuthServlet(fhirContext, authService)), "/fhir/.well-known/smart-configuration");
        context.addServlet(new ServletHolder(new AuthServlet(fhirContext, authService)), "/auth/.well-known/openid-configuration");
        context.addServlet(new ServletHolder(new AuthServlet(fhirContext, authService)), "/auth/jwks.json");
        context.addServlet(new ServletHolder(new AuthServlet(fhirContext, authService)), "/auth/token");
        context.addServlet(new ServletHolder(new AuthServlet(fhirContext, authService)), "/fhir/metadata");
        context.addServlet(new ServletHolder(new PayerWorkflowServlet(fhirContext, fixtureRoot)), "/cds-services");
        context.addServlet(new ServletHolder(new PayerWorkflowServlet(fhirContext, fixtureRoot)), "/cds-services/*");
        context.addServlet(new ServletHolder(new PayerWorkflowServlet(fhirContext, fixtureRoot)), "/fhir/Questionnaire/payerlab-dental-orthodontics");
        context.addServlet(new ServletHolder(new PayerWorkflowServlet(fhirContext, fixtureRoot)), "/fhir/QuestionnaireResponse/$submit");
        context.addServlet(new ServletHolder(new PayerWorkflowServlet(fhirContext, fixtureRoot)), "/fhir/Claim/$submit");
        context.addServlet(new ServletHolder(new FhirServlet(fhirContext, repository)), "/fhir/*");

        Server server = new Server(port);
        server.setHandler(context);
        return server;
    }
}
