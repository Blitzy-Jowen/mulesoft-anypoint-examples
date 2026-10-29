/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.upload_to_ftp_after_converting_json_to_xml;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.mulesoft.examples.upload_to_ftp_after_converting_json_to_xml.config.FtpProperties;

/**
 * Spring Boot entry point of the converted Mule example {@code upload-to-ftp-after-converting-json-to-xml}
 * (Apache License 2.0, see the project's {@code LICENSE} and {@code NOTICE}).
 *
 * <p>Replaces the original's {@code mule-deploy.properties} container descriptor, which loaded
 * {@code upload-to-ftp.xml} and its single flow {@code main} into the Mule runtime. This class starts the
 * application as a standalone process on Undertow; the package scan wires the flow's replacements:
 * {@code controller.EmployeeUploadController} for the HTTP listener on {@code /},
 * {@code service.EmployeeUploadService} with {@code mapper.EmployeesXmlMapper} for the JSON-to-XML transform,
 * {@code client.FtpUploadClient} for the FTP outbound endpoint, and {@code exception.GlobalExceptionHandler} for
 * the platform-default errors. The listener port and address and the FTP settings ({@code ftp.*}) come from
 * {@code application.yml}.
 *
 * <p>{@link FtpProperties} carries no stereotype annotation, so it is registered here, once. Startup contacts no
 * FTP server and accepts an unset host and credentials; each upload checks them before it connects.
 */
@SpringBootApplication
@EnableConfigurationProperties(FtpProperties.class)
public class UploadToFtpAfterConvertingJsonToXmlApplication {

    public static void main(String[] args) {
        SpringApplication.run(UploadToFtpAfterConvertingJsonToXmlApplication.class, args);
    }
}
