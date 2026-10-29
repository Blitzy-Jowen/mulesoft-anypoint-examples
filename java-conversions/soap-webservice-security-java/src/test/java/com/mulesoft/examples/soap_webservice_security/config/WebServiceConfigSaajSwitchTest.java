package com.mulesoft.examples.soap_webservice_security.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import javax.xml.transform.TransformerFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * Tests {@link WebServiceConfig#saajDefaultTransformerFactory()}: SAAJ's switch to the JDK's transformer factory is
 * set when absent, a value already set is left unchanged, the post-processor runs when a context holding the
 * configuration refreshes, and the factory the switch selects is the one SAAJ's service lookup finds on this class
 * path.
 *
 * <p>The switch is a JVM system property, so each test starts without it and the value found before the test is
 * restored afterwards.
 */
class WebServiceConfigSaajSwitchTest {

    private static final String PROPERTY = WebServiceConfig.SAAJ_DEFAULT_TRANSFORMER_FACTORY_PROPERTY;

    private String valueBeforeTest;

    @BeforeEach
    void clearSwitch() {
        valueBeforeTest = System.getProperty(PROPERTY);
        System.clearProperty(PROPERTY);
    }

    @AfterEach
    void restoreSwitch() {
        if (valueBeforeTest == null) {
            System.clearProperty(PROPERTY);
        }
        else {
            System.setProperty(PROPERTY, valueBeforeTest);
        }
    }

    @Test
    void absentSwitchIsSetToTrue() {
        WebServiceConfig.saajDefaultTransformerFactory().postProcessBeanFactory(new DefaultListableBeanFactory());

        assertThat(System.getProperty(PROPERTY)).isEqualTo("true");
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "true"})
    void switchAlreadySetIsLeftUnchanged(String preset) {
        System.setProperty(PROPERTY, preset);

        WebServiceConfig.saajDefaultTransformerFactory().postProcessBeanFactory(new DefaultListableBeanFactory());

        assertThat(System.getProperty(PROPERTY)).isEqualTo(preset);
    }

    /**
     * The configuration refreshes with the two collaborators its constructor needs besides its own marshaller: SC-11's
     * envelope styler and a security interceptor, here with an empty policy table, since no request is processed.
     */
    @Test
    void switchIsSetWhenAContextHoldingTheConfigurationRefreshes() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(SoapEnvelopeStyleInterceptor.class, WebServiceConfig.class);
            context.registerBean(PathScopedSecurityInterceptor.class,
                    () -> new PathScopedSecurityInterceptor(Map.of()));
            context.refresh();

            assertThat(System.getProperty(PROPERTY)).isEqualTo("true");
        }
    }

    @Test
    void switchSelectsTheFactoryTheServiceLookupFinds() {
        assertThat(TransformerFactory.newDefaultInstance()).hasSameClassAs(TransformerFactory.newInstance());
    }
}
