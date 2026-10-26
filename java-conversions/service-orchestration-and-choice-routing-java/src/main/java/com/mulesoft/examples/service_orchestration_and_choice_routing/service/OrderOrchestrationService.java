/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Owner of the {@code defaultErrorHandler} sub-flow of {@code fulfillment.xml} (lines 156-158).
 *
 * <p>{@code AuditService} runs {@code defaultErrorHandler} on its catch branch and resolves this class lazily through
 * an {@code ObjectProvider}.
 */
@Service
public class OrderOrchestrationService {

	private static final Logger LOG = LoggerFactory.getLogger(OrderOrchestrationService.class);

	/**
	 * The {@code defaultErrorHandler} sub-flow: logs {@code Error occurred: #[payload]} at INFO.
	 *
	 * @param payload the message payload when the error occurred; {@code null} is logged as {@code "null"}
	 */
	public void logError(Object payload) {
		LOG.info("Error occurred: " + String.valueOf(payload));
	}

}
