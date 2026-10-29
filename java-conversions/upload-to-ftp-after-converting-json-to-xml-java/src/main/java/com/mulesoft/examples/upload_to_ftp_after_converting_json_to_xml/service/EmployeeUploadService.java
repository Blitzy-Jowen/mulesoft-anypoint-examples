/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.upload_to_ftp_after_converting_json_to_xml.service;

import java.nio.charset.Charset;

import org.springframework.stereotype.Service;

import com.mulesoft.examples.upload_to_ftp_after_converting_json_to_xml.client.FtpUploadClient;
import com.mulesoft.examples.upload_to_ftp_after_converting_json_to_xml.mapper.EmployeesXmlMapper;

/**
 * Orchestration of flow {@code main} in
 * {@code upload-to-ftp-after-converting-json-to-xml/src/main/app/upload-to-ftp.xml}: the "JSON to XML" transform,
 * then the "Upload to FTP" outbound endpoint, then the XML as the response payload.
 *
 * <p>The flow declares no exception strategy, so nothing is caught or retried here: a mapping failure stops the
 * flow before any upload, and every exception reaches the caller unchanged.
 */
@Service
public class EmployeeUploadService {

    private final EmployeesXmlMapper employeesXmlMapper;

    private final FtpUploadClient ftpUploadClient;

    public EmployeeUploadService(EmployeesXmlMapper employeesXmlMapper, FtpUploadClient ftpUploadClient) {
        this.employeesXmlMapper = employeesXmlMapper;
        this.ftpUploadClient = ftpUploadClient;
    }

    /**
     * Converts the posted employees JSON to XML, uploads it as {@link FtpUploadClient#OUTPUT_PATTERN} and returns it.
     *
     * <p>The transform reads the body with the charset its request declared, the message encoding the Mule listener
     * set from the {@code Content-Type}, or detects UTF-8, UTF-16 or UTF-32 from the bytes when none was declared; the
     * XML is UTF-8 either way.
     *
     * @param json the request body
     * @param declaredCharset the charset the request's {@code Content-Type} declares, or {@code null} when it declares
     *                        none
     * @return the uploaded XML bytes, the same array that was stored on the FTP server
     * @throws IllegalArgumentException when the payload cannot be decoded with {@code declaredCharset}, or is empty,
     *                                  malformed, or its root is not a JSON object
     * @throws IllegalStateException when the FTP settings are missing or the server rejects the upload
     * @throws java.io.UncheckedIOException when the FTP connection or transfer fails
     */
    public byte[] process(byte[] json, Charset declaredCharset) {
        byte[] xml = employeesXmlMapper.toXmlBytes(json, declaredCharset); // dw:transform-message (upload-to-ftp.xml:9)
        ftpUploadClient.upload(FtpUploadClient.OUTPUT_PATTERN, xml); // ftp:outbound-endpoint (upload-to-ftp.xml:28)
        return xml;                                                  // HTTP response payload is the XML
    }
}
