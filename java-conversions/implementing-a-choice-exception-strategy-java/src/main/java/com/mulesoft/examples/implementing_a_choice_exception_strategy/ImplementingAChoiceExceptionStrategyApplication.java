/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.implementing_a_choice_exception_strategy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point of the converted Mule example {@code implementing-a-choice-exception-strategy} (Apache
 * License 2.0, see the project's {@code LICENSE} and {@code NOTICE}).
 *
 * <p>Replaces the original's {@code mule-deploy.properties} container descriptor, whose
 * {@code config.resources=choice-error-handling.xml} loaded the single flow {@code choice-error-handlingFlow1} into
 * the Mule runtime; this class starts the application as a standalone process on Undertow, with the listener port and
 * address taken from {@code application.yml}.
 *
 * <p>Component scanning from this package root picks up the flow's parts: {@code controller.ValidationController}
 * (the HTTP listener on {@code /}), {@code service.InputValidationService} and {@code service.InputDataValidator}
 * (the transformer, custom filter and email filter), {@code exception.ChoiceExceptionHandler} (the choice exception
 * strategy) and the SC-1 {@code exception.GlobalExceptionHandler} (the platform-default error responses).
 */
@SpringBootApplication
public class ImplementingAChoiceExceptionStrategyApplication {

    public static void main(String[] args) {
        SpringApplication.run(ImplementingAChoiceExceptionStrategyApplication.class, args);
    }
}
