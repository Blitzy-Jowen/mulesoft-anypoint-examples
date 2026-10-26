/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;

import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.config.WorkdayProperties;

/**
 * Log safety of {@link WorkdayRevenueManagementClient#reauthenticate()}: the re-authentication step logs fixed text at
 * INFO and never the UsernameToken's {@code user@tenant} principal or the password.
 *
 * <p>The client is built with identifying sentinel values for user, tenant and password, and a Logback
 * {@link ListAppender} on the client's logger, opened to every level, captures what it writes. No request is sent:
 * re-authentication only rebuilds the security interceptor.
 */
final class WorkdayRevenueManagementClientLoggingTest {

    private static final String SENTINEL_USER = "sentinel_user";

    private static final String SENTINEL_TENANT = "sentinel_tenant";

    private static final String SENTINEL_PASSWORD = "sentinel_secret";

    /** The fixed INFO text of the re-authentication step. */
    private static final String REAUTHENTICATION_MESSAGE = "Re-authenticating Workday UsernameToken";

    /** Every value no log event may contain: the token principal, its two parts, and the password. */
    private static final List<String> FORBIDDEN = List.of(SENTINEL_USER, SENTINEL_TENANT,
            SENTINEL_USER + "@" + SENTINEL_TENANT, SENTINEL_PASSWORD);

    private Logger logger;

    private Level previousLevel;

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(WorkdayRevenueManagementClient.class);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.ALL);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(previousLevel);
    }

    @Test
    void reauthenticateLogsFixedTextWithoutThePrincipalOrThePassword() {
        WorkdayRevenueManagementClient client =
                new WorkdayRevenueManagementClient(sentinelProperties(), new UpstreamCallExecutor(List.of()));

        client.reauthenticate();

        assertThat(appender.list)
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.INFO);
                    assertThat(event.getFormattedMessage()).isEqualTo(REAUTHENTICATION_MESSAGE);
                    assertThat(event.getArgumentArray()).isNullOrEmpty();
                    assertThat(event.getThrowableProxy()).isNull();
                });
        for (ILoggingEvent event : appender.list) {
            assertThat(loggedTexts(event))
                    .allSatisfy(text -> assertThat(text).doesNotContain(FORBIDDEN));
        }
    }

    /** Fake connection settings whose user, tenant and password are recognisable wherever they appear. */
    private static WorkdayProperties sentinelProperties() {
        WorkdayProperties properties = new WorkdayProperties();
        properties.setHostname("localhost");
        properties.setTenant(SENTINEL_TENANT);
        properties.setUser(SENTINEL_USER);
        properties.setPassword(SENTINEL_PASSWORD);
        properties.setEndpoint("http://localhost/ccx/service/Revenue_Management/v25.0");
        return properties;
    }

    /**
     * Collects every text an event can render: its message pattern, its formatted message, each argument, and the
     * class name and message of its throwable and of every cause and suppressed throwable.
     */
    private static List<String> loggedTexts(ILoggingEvent event) {
        List<String> texts = new ArrayList<>();
        texts.add(String.valueOf(event.getMessage()));
        texts.add(String.valueOf(event.getFormattedMessage()));
        Object[] arguments = event.getArgumentArray();
        if (arguments != null) {
            for (Object argument : arguments) {
                texts.add(String.valueOf(argument));
            }
        }
        addThrowableTexts(event.getThrowableProxy(), texts);
        return texts;
    }

    private static void addThrowableTexts(IThrowableProxy proxy, List<String> texts) {
        if (proxy == null || proxy.isCyclic()) {
            return;
        }
        texts.add(proxy.getClassName());
        texts.add(String.valueOf(proxy.getMessage()));
        addThrowableTexts(proxy.getCause(), texts);
        IThrowableProxy[] suppressed = proxy.getSuppressed();
        if (suppressed != null) {
            for (IThrowableProxy each : suppressed) {
                addThrowableTexts(each, texts);
            }
        }
    }
}
