/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.proxying_a_rest_api.config;

import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggerContextListener;
import ch.qos.logback.classic.spi.TurboFilterList;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;

/**
 * Keeps the caller's credentials and content out of the log at every level. The original flow has no logger, and
 * the proxy forwards the caller's {@code Authorization} header, cookies, query and body to the upstream without
 * recording them; the libraries that carry the exchange would record them below {@code INFO}:
 * <ul>
 *   <li>{@code org.apache.hc}, the httpclient5 transport of {@code BoxProxyClient}: {@code ...http.headers}
 *       writes every request and response header, {@code ...http.wire} every byte on the connection, bodies
 *       included, {@code ...impl.classic.MainClientExec} the request line with its query,
 *       {@code ...impl.classic.RedirectExec} each redirect {@code Location}, and the cookie, auth and response
 *       parser classes the values they handle. Every event of the hierarchy below {@code INFO} describes a
 *       proxied exchange, so the whole hierarchy is guarded.</li>
 *   <li>{@code org.springframework.web.servlet.DispatcherServlet}: the request line with its query, and, with
 *       {@code spring.mvc.log-request-details}, the request parameters and the request and response headers.
 *       Reading the parameters to log them consumes a form body, which then never reaches the upstream.</li>
 *   <li>{@code org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver} and
 *       {@code ...HttpEntityMethodProcessor}: the message of every handled exception and the 500 body that carries
 *       it, text the generic exception advice keeps out of its own log line because it can repeat request
 *       content.</li>
 * </ul>
 *
 * <p>The {@code INFO} pins of these loggers in {@code application.yml} hold against a root level only: an explicit
 * {@code logging.level.<name>} from the command line or the environment outranks the file. This filter denies
 * their {@code DEBUG} and {@code TRACE} events whatever level is configured, so {@code isDebugEnabled()} and
 * {@code isTraceEnabled()} are {@code false} for them, and the {@code DispatcherServlet} no longer reads the
 * parameters to log them. Their {@code INFO}, {@code WARN} and {@code ERROR} events, and every event of any other
 * logger, pass to Logback's level checks unchanged, so every {@code logging.*} property keeps its effect elsewhere.
 * The guarded names are fixed in code on purpose: a configuration switch would be the override the guard exists to
 * withstand.
 *
 * <p>{@link #install()} places the filter first in the turbo filter chain, ahead of any filter a custom Logback
 * configuration might add whose {@code ACCEPT} would otherwise bypass it, and registers a reset-resistant context
 * listener that puts it back whenever the context is reset, as a reconfiguration does.
 * {@link SensitiveLogGuardInstaller} installs it each time Spring Boot has initialized the logging system.
 */
public final class SensitiveLogGuard extends TurboFilter {

    /** Guarded logger names; each guards itself and every logger below it in the dotted hierarchy. */
    static final List<String> GUARDED_LOGGERS = List.of(
            "org.apache.hc",
            "org.springframework.web.servlet.DispatcherServlet",
            "org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver",
            "org.springframework.web.servlet.mvc.method.annotation.HttpEntityMethodProcessor");

    private static final String NAME = "sensitive-log-guard";

    private static final Object INSTALL_LOCK = new Object();

    /**
     * Denies an event below {@code INFO} from a guarded logger and leaves every other event to the levels.
     *
     * @return {@link FilterReply#DENY} when {@code logger} is guarded and {@code level} is below {@code INFO} or
     *         unknown, otherwise {@link FilterReply#NEUTRAL}
     */
    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level, String format, Object[] params,
                              Throwable t) {
        if (logger == null || (level != null && level.isGreaterOrEqual(Level.INFO))) {
            return FilterReply.NEUTRAL;
        }
        return isGuarded(logger.getName()) ? FilterReply.DENY : FilterReply.NEUTRAL;
    }

    /**
     * Tells whether {@code loggerName} is a guarded name or lies below one in the dotted hierarchy, as Logback
     * nests loggers: {@code org.apache.hc.client5.http.wire} is below {@code org.apache.hc}, {@code org.apache.hcx}
     * is not.
     *
     * @param loggerName a logger name, or {@code null}
     * @return {@code true} when events below {@code INFO} from that logger are denied
     */
    static boolean isGuarded(String loggerName) {
        if (loggerName == null) {
            return false;
        }
        for (String guarded : GUARDED_LOGGERS) {
            if (loggerName.startsWith(guarded)
                    && (loggerName.length() == guarded.length() || loggerName.charAt(guarded.length()) == '.')) {
                return true;
            }
        }
        return false;
    }

    /**
     * Installs the guard in the Logback context SLF4J is bound to.
     *
     * @throws IllegalStateException when SLF4J is not bound to Logback, so the application does not start without
     *                               the guard
     */
    public static void install() {
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (!(factory instanceof LoggerContext context)) {
            throw new IllegalStateException("SensitiveLogGuard needs Logback as the SLF4J binding, found "
                    + (factory == null ? "none" : factory.getClass().getName()));
        }
        install(context);
    }

    /**
     * Makes the guard the first turbo filter of {@code context}, leaving exactly one instance in its chain, and
     * keeps it there across resets of {@code context}. Calling it again changes nothing.
     *
     * @param context the Logback context to guard
     */
    static void install(LoggerContext context) {
        synchronized (INSTALL_LOCK) {
            placeFirst(context);
            if (context.getCopyOfListenerList().stream().noneMatch(ReinstallOnReset.class::isInstance)) {
                context.addListener(new ReinstallOnReset());
            }
        }
    }

    /**
     * Puts one guard at the head of the chain and removes any other instance. Installs and resets run on any
     * thread, so they take turns; only this class takes the lock, so a reset that holds Logback's own locks
     * cannot deadlock with an install.
     */
    private static void placeFirst(LoggerContext context) {
        synchronized (INSTALL_LOCK) {
            TurboFilterList filters = context.getTurboFilterList();
            TurboFilter first = filters.isEmpty() ? null : filters.get(0);
            if (!(first instanceof SensitiveLogGuard)) {
                SensitiveLogGuard guard = new SensitiveLogGuard();
                guard.setName(NAME);
                guard.setContext(context);
                guard.start();
                filters.add(0, guard);
                first = guard;
            }
            TurboFilter kept = first;
            filters.removeIf(filter -> filter instanceof SensitiveLogGuard && filter != kept);
        }
    }

    /**
     * Puts the guard back after {@link LoggerContext#reset()}, which removes every turbo filter. It survives the
     * reset itself; a {@link LoggerContext#stop()} removes it, and the next logging initialization installs both
     * again.
     */
    private static final class ReinstallOnReset implements LoggerContextListener {

        @Override
        public boolean isResetResistant() {
            return true;
        }

        @Override
        public void onReset(LoggerContext context) {
            placeFirst(context);
        }

        @Override
        public void onStart(LoggerContext context) {
            // Starting a context removes no turbo filter.
        }

        @Override
        public void onStop(LoggerContext context) {
            // Logback drops every context listener after a stop; the next initialization installs the guard again.
        }

        @Override
        public void onLevelChange(Logger logger, Level level) {
            // A level change leaves the turbo filter chain as it is, and the guard ignores configured levels.
        }
    }
}
