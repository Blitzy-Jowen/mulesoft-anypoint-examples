/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.http_multipart_request;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point of the converted Mule example {@code http-multipart-request} (Apache License 2.0, see the
 * project's {@code LICENSE} and {@code NOTICE}).
 *
 * <p>Replaces the original's {@code mule-deploy.properties} container descriptor, whose
 * {@code config.resources=http-multipart-request.xml} loaded the flows {@code httpRenderFlow} and
 * {@code httpMultipartRequestFlow} into the Mule runtime; this class starts the application as a standalone process
 * on Undertow, so the reason phrase {@code File was uploaded.} reaches the wire. The listener port and address, the
 * multipart limits and the upload directory are taken from {@code application.yml}.
 *
 * <p>Component scanning from this package root picks up the flows' parts: {@code controller.UploadController} (the
 * two {@code /uploadFile} listeners), {@code service.UploadService} (the template, file-name extraction, content-type
 * logger and reason phrase), {@code service.FileOutboundWriter} (the SC-6 file outbound endpoint),
 * {@code mapper.ParseTemplateRenderer} (the SC-14 parse-template), {@code config.MultipartHeaderCharsetConfig} (the
 * listener's ISO-8859-1 decoding of multipart part headers), {@code config.PreflightDispatchConfig} and the SC-1
 * {@code exception.GlobalExceptionHandler} (the platform-default error responses).
 */
@SpringBootApplication
public class HttpMultipartRequestApplication {

    public static void main(String[] args) {
        SpringApplication.run(HttpMultipartRequestApplication.class, args);
    }
}
