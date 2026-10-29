/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.http_oauth_provider;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.mulesoft.examples.http_oauth_provider.config.OAuthProviderProperties;

/**
 * Spring Boot entry point of the converted Mule example {@code http-oauth-provider} (Apache License 2.0, see the
 * project's {@code LICENSE} and {@code NOTICE}), replacing the original's {@code mule-deploy.properties}, which
 * loaded {@code http-oauth-provider.xml} into the Mule runtime. It boots the OAuth 2.0 provider on
 * {@code localhost:${http.provider.port}} ({@code /authorize}, {@code /token}) and the protected listener on
 * {@code localhost:${http.listener.port}} ({@code /resources}, {@code /redirect}).
 *
 * <p>{@link OAuthProviderProperties} carries no stereotype annotation, so it is registered here, once. Properties
 * classes are deliberately not scanned: {@code config.ListenerPorts} already registers its own {@code listeners}
 * properties, which a scan would register a second time.
 */
@SpringBootApplication
@EnableConfigurationProperties(OAuthProviderProperties.class)
public class HttpOauthProviderApplication {

    public static void main(String[] args) {
        SpringApplication.run(HttpOauthProviderApplication.class, args);
    }
}
