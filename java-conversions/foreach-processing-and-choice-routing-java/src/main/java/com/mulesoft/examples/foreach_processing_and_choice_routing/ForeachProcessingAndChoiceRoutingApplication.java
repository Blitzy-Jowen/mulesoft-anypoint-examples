package com.mulesoft.examples.foreach_processing_and_choice_routing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point of the converted Mule example {@code foreach-processing-and-choice-routing}.
 *
 * <p>Replaces the Mule application {@code loanbroker-simple.xml}, which the original's {@code mule-deploy.properties}
 * loaded into the Mule runtime: the loan broker flow {@code loan-broker-sync} on 11081, the mock credit agency flow
 * {@code TheCreditAgencyService} on 18080 and the five mock bank flows {@code Bank1Flow} to {@code Bank5Flow} on
 * 10080 to 50080, all in one process as before. Ports and routes come from {@code application.yml}.
 *
 * <p>Spring WS is configured by the project's {@code WsConfigurer} alone; the {@code EnableWs} annotation is
 * deliberately absent, because it switches off Boot's web-services auto-configuration and with it the
 * {@code MessageDispatcherServlet} that serves the credit agency and the banks.
 */
@SpringBootApplication
public class ForeachProcessingAndChoiceRoutingApplication {

    public static void main(String[] args) {
        SpringApplication.run(ForeachProcessingAndChoiceRoutingApplication.class, args);
    }
}
