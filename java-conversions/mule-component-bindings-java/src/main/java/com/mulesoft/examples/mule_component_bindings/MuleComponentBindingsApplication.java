/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_component_bindings;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point of the converted Mule example {@code mule-component-bindings} (Apache License 2.0, see the
 * project's {@code LICENSE} and {@code NOTICE}).
 *
 * <p>Replaces the original's {@code mule-deploy.properties} container descriptor, which loaded
 * {@code mule-component-bindings.xml} into the Mule runtime. Its three flows are carried by the beans of the
 * subpackages: the Twitter search on {@code localhost:8081 /}, the stock statistics resource on
 * {@code localhost:8180 /api/stockStats}, and the VM {@code TwitterFlow} behind the component binding, now a
 * directly injected service. Ports, hosts and keys come from {@code application.yml}; the second listener is added
 * by {@code config.ListenerPorts}.
 */
@SpringBootApplication
public class MuleComponentBindingsApplication {

    public static void main(String[] args) {
        SpringApplication.run(MuleComponentBindingsApplication.class, args);
    }
}
