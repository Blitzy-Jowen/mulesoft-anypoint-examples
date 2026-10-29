/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.service;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.stereotype.Component;
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
 *       transaction rolls back, and the exception reaches the {@link AsyncUncaughtExceptionHandler} of
 *       {@link AsyncFailureLogging}, which logs the method and the exception's type identifiers without its text.
 *       The VM redelivery that followed a rollback in Mule has no counterpart here.</li>
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

	/** Longest logged class, method or file name, in characters after escaping. */
	private static final int NAME_LOG_LIMIT = 256;

	/** Number of causes named after the outermost exception before the chain is cut short. */
	private static final int CAUSE_LOG_LIMIT = 10;

	private static final String NULL_LOG_TOKEN = "(null)";

	private static final String TRUNCATION_LOG_MARKER = "...[truncated]";

	private static final String CAUSE_SEPARATOR = " <- ";

	private static final String MORE_CAUSES_MARKER = "...";

	private static final String CIRCULAR_CAUSE_MARKER = "[circular]";

	private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

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
			// Mule's exception strategies log the exception before running their branch. Only its type identifiers
			// are logged, never its text: database messages can quote bound values, such as the caller's order ID
			// in a truncation error. defaultErrorHandler logs the order as the original does.
			LOG.error("Audit insert into order_audits failed: {}", describe(e));
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

	/**
	 * Returns the type identifiers of {@code ex} without any of its text: its class name and throw-site frame, then
	 * the class name of each cause, separated by {@code " <- "}, for example {@code
	 * org.springframework.dao.DataIntegrityViolationException at a.b.C.d(C.java:18) <- java.sql.SQLDataException}.
	 * No message, cause message, suppressed exception or stack trace is included, because database messages can quote
	 * the bound values of the failed statement. The walk stops at a cause already named, marked {@code [circular]},
	 * and after {@link #CAUSE_LOG_LIMIT} causes, marked {@code ...}, so cyclic and very deep chains terminate. Every
	 * name is bounded and escaped by {@link #sanitize(String, int)}, so the description stays on one line.
	 */
	private static String describe(Throwable ex) {
		StringBuilder out = new StringBuilder(128);
		out.append(sanitize(ex.getClass().getName(), NAME_LOG_LIMIT));
		appendThrowSite(out, ex);
		Set<Throwable> named = Collections.newSetFromMap(new IdentityHashMap<>());
		named.add(ex);
		int causes = 0;
		for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause()) {
			out.append(CAUSE_SEPARATOR);
			if (causes == CAUSE_LOG_LIMIT) {
				out.append(MORE_CAUSES_MARKER);
				break;
			}
			if (!named.add(cause)) {
				out.append(CIRCULAR_CAUSE_MARKER);
				break;
			}
			out.append(sanitize(cause.getClass().getName(), NAME_LOG_LIMIT));
			causes++;
		}
		return out.toString();
	}

	/**
	 * Appends {@code " at <class>.<method>(<file>:<line>)"} for the frame that threw {@code ex}, in the form
	 * {@link StackTraceElement#toString()} uses but without its class loader and module prefix, or nothing when the
	 * exception has no stack trace.
	 */
	private static void appendThrowSite(StringBuilder out, Throwable ex) {
		StackTraceElement[] trace = ex.getStackTrace();
		if (trace == null || trace.length == 0 || trace[0] == null) {
			return;
		}
		StackTraceElement frame = trace[0];
		out.append(" at ")
				.append(sanitize(frame.getClassName(), NAME_LOG_LIMIT))
				.append('.')
				.append(sanitize(frame.getMethodName(), NAME_LOG_LIMIT))
				.append('(');
		if (frame.isNativeMethod()) {
			out.append("Native Method");
		} else if (frame.getFileName() == null) {
			out.append("Unknown Source");
		} else {
			out.append(sanitize(frame.getFileName(), NAME_LOG_LIMIT));
			if (frame.getLineNumber() >= 0) {
				out.append(':').append(frame.getLineNumber());
			}
		}
		out.append(')');
	}

	/**
	 * Makes {@code value} safe to log on one line.
	 *
	 * <p>ISO control characters (C0, DEL and C1, so CR, LF, TAB, NUL and NEL included), line and paragraph
	 * separators, format characters (bidirectional overrides and isolates, zero-width characters, the byte order
	 * mark), unpaired surrogates and the reverse solidus are each written as {@code \}{@code u} and four lower-case
	 * hex digits per UTF-16 unit; escaping the reverse solidus keeps an escape in the log distinguishable from the
	 * same six characters in the input. Every other character, a valid surrogate pair included, is copied unchanged.
	 *
	 * @param value the value to log; {@code null} is written as {@code (null)}
	 * @param limit the most characters the escaped value may occupy; a longer value is cut before the first character
	 *              or escape that would exceed it, never inside a surrogate pair or an escape, and
	 *              {@code ...[truncated]} is appended
	 */
	private static String sanitize(String value, int limit) {
		if (value == null) {
			return NULL_LOG_TOKEN;
		}
		StringBuilder out = new StringBuilder(Math.min(value.length(), limit) + TRUNCATION_LOG_MARKER.length());
		int i = 0;
		while (i < value.length()) {
			int codePoint = value.codePointAt(i);
			int units = Character.charCount(codePoint);
			boolean escape = mustEscape(codePoint);
			if (out.length() + (escape ? 6 * units : units) > limit) {
				return out.append(TRUNCATION_LOG_MARKER).toString();
			}
			for (int unit = i; unit < i + units; unit++) {
				if (escape) {
					appendUnicodeEscape(out, value.charAt(unit));
				} else {
					out.append(value.charAt(unit));
				}
			}
			i += units;
		}
		return out.toString();
	}

	private static boolean mustEscape(int codePoint) {
		if (codePoint == '\\' || Character.isISOControl(codePoint)) {
			return true;
		}
		int type = Character.getType(codePoint);
		return type == Character.LINE_SEPARATOR
				|| type == Character.PARAGRAPH_SEPARATOR
				|| type == Character.FORMAT
				|| type == Character.SURROGATE;
	}

	private static void appendUnicodeEscape(StringBuilder out, char c) {
		out.append("\\u")
				.append(HEX_DIGITS[(c >> 12) & 0xF])
				.append(HEX_DIGITS[(c >> 8) & 0xF])
				.append(HEX_DIGITS[(c >> 4) & 0xF])
				.append(HEX_DIGITS[c & 0xF]);
	}

	/**
	 * Logs the failures that escape {@link Async @Async} methods, which the rollback branch of
	 * {@link AuditService#audit(Order, String)} produces by design when it rethrows a failed insert.
	 *
	 * <p>Without it, Spring's default {@code SimpleAsyncUncaughtExceptionHandler} would hand the exception itself to
	 * the logger, printing the database message and cause chain and, with them, bound values such as the caller's
	 * order ID. This handler logs instead, at ERROR, the method's declaring class and name and the exception's type
	 * identifiers in the form the failed-insert line of {@code audit} uses; the method's arguments, which hold the
	 * order, are never logged.
	 *
	 * <p>It is the application's only {@link AsyncConfigurer}: Spring accepts at most one, so every asynchronous
	 * method of the application reports its failures here. {@link #getAsyncExecutor()} keeps its {@code null}
	 * default, so asynchronous methods still run on the executor Spring resolves by default, Spring Boot's
	 * {@code applicationTaskExecutor} (alias {@code taskExecutor}).
	 */
	@Component
	public static class AsyncFailureLogging implements AsyncConfigurer {

		/**
		 * @return a handler that logs the failed method and the exception's type identifiers at ERROR, never the
		 *         exception's text or the method's arguments
		 */
		@Override
		public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
			return (ex, method, params) -> LOG.error("Unexpected exception occurred invoking async method {}.{}: {}",
					sanitize(method.getDeclaringClass().getName(), NAME_LOG_LIMIT),
					sanitize(method.getName(), NAME_LOG_LIMIT),
					describe(ex));
		}
	}
}
