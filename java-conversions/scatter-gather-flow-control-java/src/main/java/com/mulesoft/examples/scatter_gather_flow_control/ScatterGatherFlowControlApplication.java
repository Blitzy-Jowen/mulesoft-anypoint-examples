/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.scatter_gather_flow_control;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point of the converted Mule example {@code scatter-gather-flow-control} (Apache License 2.0, see
 * the project's {@code LICENSE} and {@code NOTICE}).
 *
 * <p>Replaces the original's {@code mule-deploy.properties} container descriptor, which loaded
 * {@code scatter-gather.xml} and its flows {@code scatter-gatherFlow} and {@code outboundFlow} into the Mule runtime.
 * This class starts the application as a standalone process on Undertow; the package scan wires the flows'
 * replacements: {@code controller.ScatterGatherController} for the HTTP listener on {@code /scatterGather},
 * {@code service.ScatterGatherService} with {@code service.ContactMergeAggregationStrategy} for the scatter-gather
 * scope and its custom aggregation strategy, {@code mapper.ContactsCsvMapper} for the CSV transform,
 * {@code client.AggregationReportMailer} for the SMTP outbound endpoint, and {@code exception.GlobalExceptionHandler}
 * for the platform-default errors. The listener port and address and the {@code smtp.*} and {@code mail.*} settings
 * come from {@code application.yml}.
 *
 * <p>The class declares no beans: the scatter-gather routes run on Boot's auto-configured
 * {@code applicationTaskExecutor}, which backs off as soon as any other {@code Executor} bean exists.
 */
@SpringBootApplication
public class ScatterGatherFlowControlApplication {

    public static void main(String[] args) {
        SpringApplication.run(ScatterGatherFlowControlApplication.class, args);
    }
}
