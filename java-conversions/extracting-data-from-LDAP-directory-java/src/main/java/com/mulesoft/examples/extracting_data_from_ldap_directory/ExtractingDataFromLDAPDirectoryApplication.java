/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.extracting_data_from_ldap_directory;

import com.mulesoft.examples.extracting_data_from_ldap_directory.config.LdapProperties;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** Spring Boot replacement for Mule flow {@code ldapFlow1} of {@code ldap.xml}. */
@SpringBootApplication
@EnableConfigurationProperties(LdapProperties.class)
public class ExtractingDataFromLDAPDirectoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExtractingDataFromLDAPDirectoryApplication.class, args);
    }
}
