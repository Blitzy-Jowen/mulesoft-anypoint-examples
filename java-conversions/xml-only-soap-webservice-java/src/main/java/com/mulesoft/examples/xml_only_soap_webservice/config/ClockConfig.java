/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.xml_only_soap_webservice.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The single source of "now" for the PatientService and EHRService mock response mappers, which reproduce the
 * DataWeave {@code now} of {@code mocks.xml} ({@code PatientId}, {@code patientId}, {@code nationalId},
 * {@code episodeId}, {@code startDate}, {@code endDate}).
 *
 * <p>DataWeave {@code now} is a date-time in the JVM default time zone, so the clock is
 * {@link Clock#systemDefaultZone()}; a UTC clock would change the offset those values carry.
 *
 * <p>Unit tests substitute {@code Clock.fixed(...)}, through a {@code @Primary} bean or by constructing the mappers
 * with it, to make the values deterministic. This is not a parity device: the original's captured times remain
 * nondeterministic and are never reproduced through this clock.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    /**
     * Returns the clock every mock {@code now} reads.
     *
     * @return the system clock in the JVM default time zone
     */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
