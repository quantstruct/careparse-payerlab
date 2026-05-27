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

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        context.setContextPath("/");
        context.addFilter(
                new FilterHolder(new EndpointRestrictionFilter(fhirContext)),
                "/fhir/*",
                EnumSet.of(DispatcherType.REQUEST));
        context.addServlet(new ServletHolder(new FhirServlet(fhirContext, repository)), "/fhir/*");

        Server server = new Server(port);
        server.setHandler(context);
        return server;
    }
}
