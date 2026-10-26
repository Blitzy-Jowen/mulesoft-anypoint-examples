/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.service;

import java.math.BigDecimal;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.mulesoft.examples.service_orchestration_and_choice_routing.model.orders.Order;

/**
 * The {@code auditService} flow of {@code fulfillment.xml} (lines 130-143): records each processed order in
 * {@code order_audits}.
 *
 * <p>{@code orderService} dispatches the order after its item loop through the one-way VM endpoint {@code audit}
 * (line 44), with the session variable {@code totalValue} travelling beside it. The one-way dispatch becomes
 * {@link Async @Async}: the caller returns at once, and the insert runs on the task executor in its own JTA
 * transaction ({@code xa-transaction action="ALWAYS_BEGIN"}), so the audit row commits independently of the order
 * write. The asynchronous interceptor is outermost, so the transaction is opened on the executor thread.
 *
 * <p>The flow's {@code choice-exception-strategy} is reproduced branch by branch, in declaration order:
 * <ol>
 *   <li>{@code rollback-exception-strategy when="#[sessionVars.totalValue] > 5000"}: the failure is rethrown, the
 *       transaction rolls back, and the exception reaches Spring's {@code AsyncUncaughtExceptionHandler}, which logs
 *       it. The VM redelivery that followed a rollback in Mule has no counterpart here.</li>
 *   <li>{@code catch-exception-strategy}: {@code defaultErrorHandler} runs
 *       ({@link OrderOrchestrationService#logError(Object)}) and the transaction commits.</li>
 * </ol>
 *
 * <p>{@code OrderOrchestrationService} calls this class, and this class calls it back only on the catch branch, so it
 * is resolved lazily through an {@link ObjectProvider} and never during construction.
 */
@Service
public class AuditService {

	private static final Logger LOG = LoggerFactory.getLogger(AuditService.class);

	/** {@code Save OrderSummary} (line 135); the identity column {@code i} takes its default. */
	static final String INSERT_ORDER_AUDIT = "insert into order_audits values(default, ?, ?)";

	private final JdbcTemplate jdbcTemplate;

	private final ObjectProvider<OrderOrchestrationService> orderOrchestrationService;

	private final BigDecimal rollbackThreshold;

	/**
	 * @param jdbcTemplate              the template over the XA Derby {@code DataSource} of
	 *                                  {@code config/XaTransactionConfig}
	 * @param orderOrchestrationService lazy access to the owner of {@code defaultErrorHandler}
	 * @param rollbackThreshold         the total above which a failed insert rolls back instead of being logged,
	 *                                  {@code orders.audit.rollback-threshold}
	 */
	public AuditService(JdbcTemplate jdbcTemplate,
			ObjectProvider<OrderOrchestrationService> orderOrchestrationService,
			@Value("${orders.audit.rollback-threshold:5000}") BigDecimal rollbackThreshold) {
		this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
		this.orderOrchestrationService = Objects.requireNonNull(orderOrchestrationService,
				"orderOrchestrationService");
		this.rollbackThreshold = Objects.requireNonNull(rollbackThreshold, "rollbackThreshold");
	}

	/**
	 * Inserts the audit row of an order in a new transaction on the task executor, returning to the caller at once.
	 *
	 * <p>{@code totalValue} is bound as the string the order flow built (for example {@code "02550"}), and Derby
	 * converts it for the integer column {@code total_value}, as the original insert of the session variable did. A
	 * failed insert rolls back and propagates when {@link #exceedsRollbackThreshold(String)} holds; otherwise
	 * {@code defaultErrorHandler} logs the order and the transaction commits.
	 *
	 * @param order      the processed order, whose {@code orderId} is audited
	 * @param totalValue the order's session variable {@code totalValue}
	 */
	@Async
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void audit(Order order, String totalValue) {
		try {
			jdbcTemplate.update(INSERT_ORDER_AUDIT, order.getOrderId(), totalValue);
		} catch (RuntimeException e) {
			// Mule's exception strategies log the exception before running their branch. The order ID is caller
			// input and stays out of this line; defaultErrorHandler logs the order as the original does.
			LOG.error("Audit insert into order_audits failed", e);
			if (exceedsRollbackThreshold(totalValue)) {
				throw e;
			}
			orderOrchestrationService.getObject().logError(order);
		}
	}

	/**
	 * The {@code when} condition of the rollback branch, {@code #[sessionVars.totalValue] > 5000}.
	 *
	 * @param totalValue the order's {@code totalValue}; surrounding whitespace is ignored
	 * @return {@code true} when it is a number greater than the rollback threshold; {@code false} when it is
	 *         {@code null}, not a number, or not greater, which selects the catch branch
	 */
	public boolean exceedsRollbackThreshold(String totalValue) {
		if (totalValue == null) {
			return false;
		}
		try {
			return new BigDecimal(totalValue.trim()).compareTo(rollbackThreshold) > 0;
		} catch (NumberFormatException e) {
			return false;
		}
	}
}
