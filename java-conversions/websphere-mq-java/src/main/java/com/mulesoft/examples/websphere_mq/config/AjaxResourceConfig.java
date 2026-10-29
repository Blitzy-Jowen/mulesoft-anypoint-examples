package com.mulesoft.examples.websphere_mq.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Static surface of the Mule Ajax connector, shared component SC-12: the pages of the connector's
 * {@code resourceBase} docroot and the Mule Ajax JavaScript library, at the paths the copied pages load.
 *
 * <p>Every path hangs off the connector's server path, the path of its {@code serverUrl}, configured as
 * {@code ajax.server-path}:
 * <ul>
 *   <li>{@code <server path>/mule-resource/js/**} serves {@code classpath:/mule-resource/js/}, the Mule 3.8.0 Ajax
 *       JavaScript copied unchanged;</li>
 *   <li>{@code <server path>/**} serves {@code classpath:/static<server path>/}, the docroot copied unchanged;</li>
 *   <li>{@code <server path>/} forwards to {@code <server path>/index.html}, the welcome file Jetty served for the
 *       docroot;</li>
 *   <li>{@code <server path>} answers 302 with the absolute {@code Location} {@code <request root URL><server path>/},
 *       Jetty's redirect for a directory requested without its trailing slash.</li>
 * </ul>
 * Files are served byte for byte, with no cache period and no resource chain, under the content type the resource
 * handler derives from the file name. A file that does not exist raises {@code NoResourceFoundException}, which
 * SC-1 answers with the no-listener 404. Only GET and HEAD serve content; OPTIONS lists them, and every other
 * method is answered with 405. Jetty's MIME table, its redirect and its method handling are Tier 2A verification
 * points.
 *
 * <p>The handlers are registered for every listener: SC-10's route for the Ajax port in {@code application.yml} is
 * what confines these paths to that listener. The forward is a FORWARD dispatch, which SC-10's request-only filter
 * does not route again. The registrations do not depend on {@code spring.web.resources.add-mappings}, which only
 * switches Boot's default {@code /**} mapping. {@code @EnableWebMvc} is deliberately absent, because it would
 * replace Boot's MVC auto-configuration, including the view resolver that resolves the {@code forward:} view.
 *
 * <p>The class names no project value, so every project serving the Mule Ajax transport carries a copy that differs
 * from this one in the {@code package} line alone.
 */
@Configuration(proxyBeanMethods = false)
public class AjaxResourceConfig implements WebMvcConfigurer {

    private static final String SLASH = "/";

    private static final String SCRIPT_PATTERN_SUFFIX = "/mule-resource/js/**";

    private static final String SCRIPT_LOCATION = "classpath:/mule-resource/js/";

    private static final String DOCROOT_PATTERN_SUFFIX = "/**";

    private static final String DOCROOT_LOCATION_PREFIX = "classpath:/static";

    private static final String WELCOME_FILE = "/index.html";

    /** Characters that would turn the literal server path into a wildcard or a template variable of the patterns. */
    private static final String PATTERN_CHARACTERS = "*?{}\\";

    private final String serverPath;

    /**
     * Creates the configuration for the connector's server path.
     *
     * @param serverPath the path of the connector's {@code serverUrl}; surrounding blanks are trimmed, a missing
     *                   leading {@code /} is added and trailing {@code /} are removed
     * @throws IllegalStateException when nothing but the root path remains, or when a segment is empty, {@code .},
     *                               {@code ..} or holds a pattern character ({@code * ? { } \})
     */
    public AjaxResourceConfig(@Value("${ajax.server-path}") String serverPath) {
        this.serverPath = normalize(serverPath);
    }

    /**
     * Registers the Mule Ajax JavaScript ahead of the docroot, so a script path is never looked up in the docroot.
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler(serverPath + SCRIPT_PATTERN_SUFFIX)
                .addResourceLocations(SCRIPT_LOCATION);
        registry.addResourceHandler(serverPath + DOCROOT_PATTERN_SUFFIX)
                .addResourceLocations(DOCROOT_LOCATION_PREFIX + serverPath + SLASH);
    }

    /**
     * Maps the docroot directory itself: its welcome file with the trailing slash, the redirect to it without.
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController(serverPath + SLASH)
                .setViewName("forward:" + serverPath + WELCOME_FILE);
        // No explicit status code: with one, the redirect view writes the relative Location itself, while without
        // one it calls the container's sendRedirect, which answers 302 with an absolute Location built from the
        // request, as Jetty's sendRedirect did for the original docroot.
        registry.addRedirectViewController(serverPath, serverPath + SLASH);
    }

    /**
     * Normalizes the configured server path to {@code /segment[/segment...]} and rejects every value that is not a
     * literal path below the root: the root itself is no valid server path, and the value becomes part of URL
     * patterns and of a classpath location.
     */
    private static String normalize(String configured) {
        String path = configured == null ? "" : configured.trim();
        if (!path.startsWith(SLASH)) {
            path = SLASH + path;
        }
        int end = path.length();
        while (end > 0 && path.charAt(end - 1) == '/') {
            end--;
        }
        path = path.substring(0, end);
        if (path.isEmpty()) {
            throw new IllegalStateException(
                    "ajax.server-path must name a path below the root, but is '" + configured + "'");
        }
        for (String segment : path.substring(1).split(SLASH, -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment) || hasPatternCharacter(segment)) {
                throw new IllegalStateException(
                        "ajax.server-path must be a literal path, but is '" + configured + "'");
            }
        }
        return path;
    }

    private static boolean hasPatternCharacter(String segment) {
        for (int i = 0; i < segment.length(); i++) {
            if (PATTERN_CHARACTERS.indexOf(segment.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }
}
