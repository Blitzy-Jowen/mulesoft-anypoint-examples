/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.rest_api_with_apikit.controller;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mulesoft.examples.rest_api_with_apikit.exception.ApiKitNotFoundException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.UriUtils;

/**
 * The APIkit console and RAML descriptor the original {@code apikit:config consoleEnabled="true"
 * consolePath="console"} served: shared component SC-17 of the conversion plan, reproducing
 * {@code ConsoleHandler} and {@code RamlDescriptorHandler} of {@code mule-module-apikit} 3.8.0.
 *
 * <p>Served under the API base path {@code apikit.base-path} (default {@code /api}) with the console path
 * {@code apikit.console-path} (default {@code console}):
 * <ul>
 *   <li>{@code GET <base>/console}: 301 to {@code <base>/console/};</li>
 *   <li>{@code GET <base>/console/} and {@code <base>/console/index.html}: the console page, its RAML source
 *       pointed at {@code ./?raml} and its attribute placeholder replaced by the handler's options;</li>
 *   <li>{@code GET <base>/console/<file>}: the console's scripts, styles, fonts and images from the classpath
 *       tree {@code apikit-console/};</li>
 *   <li>{@code GET <base>/console?raml}, {@code GET <base>/console/?raml}, the same paths and {@code GET <base>/}
 *       with an {@code Accept} header naming {@code application/raml+yaml}: the RAML of {@code apikit.raml}
 *       with its {@code baseUri} set to the listener URL;</li>
 *   <li>{@code GET <base>/<include>} and {@code GET <base>/console/<include>}: each file the RAML
 *       {@code !include}s, so the console resolves includes relative to the RAML it loaded.</li>
 * </ul>
 * Every miss throws {@link ApiKitNotFoundException}, which SC-3 answers with APIkit's 404 body. The class maps
 * no pattern broader than these, so unknown resources and methods under the base path still reach the APIkit
 * 404 and 405 mappings of SC-3; a method other than GET or HEAD on a console path therefore receives the 405.
 *
 * <p>Tier 2A verification points, where the bytes are a plan decision rather than recorded original output:
 * <ul>
 *   <li>the console is the jar's {@code console/} tree, while APIkit 3.8.0 serves {@code console2/} by
 *       default;</li>
 *   <li>the RAML is the raw file with only its {@code baseUri} line rewritten, while APIkit re-dumps the parsed
 *       RAML with every include inlined;</li>
 *   <li>the content types of the include files, which follow the console's extension table, so {@code .json}
 *       files are {@code application/octet-stream};</li>
 *   <li>the page's RAML source is {@code ./?raml}, while {@code ConsoleHandler} with its RAML 0.8 parser wrote
 *       {@code ./?};</li>
 *   <li>the redirect's {@code Location} is the relative path {@code <base>/console/}, while
 *       {@code ConsoleHandler} wrote an absolute URL built from the scheme, the {@code Host} header and the
 *       request path.</li>
 * </ul>
 *
 * <p>Every RAML-backed project carries a copy that differs from this one in the {@code package} line and the
 * package root of the {@link ApiKitNotFoundException} import alone; all project specifics come from the three
 * {@code apikit.*} properties.
 */
@RestController
public class ApiKitConsoleController implements SmartInitializingSingleton {

    /** Media type of the RAML descriptor. */
    public static final String RAML_MEDIA_TYPE = "application/raml+yaml";

    /** Classpath directory holding the console files. */
    public static final String CONSOLE_ROOT = "apikit-console/";

    /** Options {@code ConsoleHandler} wrote in place of the page's {@code console-attributes-placeholder}. */
    public static final String CONSOLE_ATTRIBUTES = "disable-raml-client-generator=\"\" disable-theme-switcher=\"\"";

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiKitConsoleController.class);

    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private static final String HTML_CONTENT_TYPE = "text/html";

    /** {@code ConsoleHandler}'s content types by lower-cased file extension; anything else is octet-stream. */
    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "html", HTML_CONTENT_TYPE,
            "css", "text/css",
            "js", "application/x-javascript",
            "png", "image/png",
            "gif", "image/gif",
            "svg", "image/svg+xml",
            "raml", RAML_MEDIA_TYPE);

    private static final String INDEX_FILE = "index.html";

    private static final String RAML_CONSOLE_SRC = "<raml-console src=\"[^\"]+\"";

    private static final String RAML_CONSOLE_SRC_RENDERED = "<raml-console src=\"./?raml\"";

    private static final String ATTRIBUTES_PLACEHOLDER = "console-attributes-placeholder";

    private static final String NOT_FOUND_MESSAGE = "Resource not found";

    private static final Pattern INCLUDE = Pattern.compile("!include\\s+(\\S+)");

    private static final Pattern BASE_URI_LINE = Pattern.compile("(?m)^baseUri:.*$");

    private static final Pattern TITLE_LINE = Pattern.compile("(?m)^title:.*$");

    private static final Pattern FIRST_LINE = Pattern.compile("(?m)^.*$");

    private static final Pattern LINE_BREAK = Pattern.compile("\\R");

    /** Characters an include target may hold, so that it is a literal path pattern. */
    private static final Pattern SAFE_INCLUDE = Pattern.compile("[A-Za-z0-9._/-]+");

    private final String basePath;

    private final String consolePrefix;

    private final String ramlPath;

    private final String ramlText;

    /** Include target, as written in the RAML, to its bytes, in encounter order. */
    private final Map<String, byte[]> includes;

    private final ApplicationContext applicationContext;

    /** Console files already read, keyed by their canonical path below {@link #CONSOLE_ROOT}; misses never. */
    private final Map<String, byte[]> consoleFiles = new ConcurrentHashMap<>();

    private final AtomicBoolean registered = new AtomicBoolean();

    private volatile byte[] renderedIndex;

    /**
     * Loads the RAML and the files it includes.
     *
     * @param basePath the API base path, the original listener path without its {@code /*}
     * @param consolePath the APIkit {@code consolePath}
     * @param ramlPath the classpath location of the RAML, for example {@code leagues.raml} or
     *                 {@code api/netsuite-api.raml}
     * @param applicationContext the context whose {@code requestMappingHandlerMapping} receives the include
     *                           mappings
     * @throws IllegalStateException if the RAML is not a readable classpath resource
     */
    public ApiKitConsoleController(@Value("${apikit.base-path:/api}") String basePath,
                                   @Value("${apikit.console-path:console}") String consolePath,
                                   @Value("${apikit.raml}") String ramlPath,
                                   ApplicationContext applicationContext) {
        this.basePath = stripTrailingSlashes(basePath == null ? "" : basePath.trim());
        this.consolePrefix = this.basePath + "/" + stripSlashes(consolePath == null ? "" : consolePath.trim());
        this.ramlPath = stripLeadingSlashes(ramlPath == null ? "" : ramlPath.trim());
        this.applicationContext = applicationContext;

        ClassPathResource raml = new ClassPathResource(this.ramlPath);
        if (this.ramlPath.isEmpty() || !raml.isReadable()) {
            throw new IllegalStateException("APIkit RAML '" + this.ramlPath + "' (apikit.raml) is not a readable "
                    + "classpath resource");
        }
        this.ramlText = new String(read(raml), StandardCharsets.UTF_8);
        this.includes = loadIncludes(ramlText, this.ramlPath.substring(0, this.ramlPath.lastIndexOf('/') + 1));
        LOGGER.info("APIkit console for {} at {}/ ({} RAML includes)", this.ramlPath, consolePrefix,
                includes.size());
    }

    /**
     * {@code RamlDescriptorHandler} on the API root: the RAML to a GET (or HEAD) whose {@code Accept} header
     * names {@code application/raml+yaml}. Every other request to {@code <base>/} is the unknown resource it
     * was for APIkit.
     *
     * <p>Mapped for every method so that a non-RAML request answers APIkit's 404 rather than a 405. The
     * trailing {@code /} is significant: Spring matches no optional trailing slash, so neither {@code <base>}
     * nor any resource below it reaches this method.
     */
    @RequestMapping("${apikit.base-path:/api}/")
    public ResponseEntity<byte[]> apiRoot(HttpServletRequest request) {
        if (isGetOrHead(request) && wantsRaml(request)) {
            return raml(request);
        }
        throw new ApiKitNotFoundException(NOT_FOUND_MESSAGE);
    }

    /**
     * The console path without its trailing slash: the RAML when asked for, otherwise a 301 whose
     * {@code Location} is the relative path {@code <base>/console/}, preceded by the servlet context path and
     * followed by the query string when there is one. The path is built from the configured console path
     * alone, so neither the {@code Host} header nor the raw request path reaches the header.
     */
    @GetMapping("${apikit.base-path:/api}/${apikit.console-path:console}")
    public ResponseEntity<byte[]> consoleNoSlash(HttpServletRequest request) {
        if (hasRamlQuery(request) || wantsRaml(request)) {
            return raml(request);
        }
        String contextPath = request.getContextPath();
        StringBuilder location = new StringBuilder(contextPath == null ? "" : contextPath)
                .append(UriUtils.encodePath(consolePrefix, StandardCharsets.UTF_8)).append('/');
        String query = request.getQueryString();
        if (query != null && !query.isEmpty()) {
            location.append('?').append(query);
        }
        return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
                .header(HttpHeaders.LOCATION, location.toString())
                .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                .contentLength(0)
                .build();
    }

    /** The console root: the RAML when asked for, otherwise the console page. */
    @GetMapping("${apikit.base-path:/api}/${apikit.console-path:console}/")
    public ResponseEntity<byte[]> consoleRoot(HttpServletRequest request) {
        if (hasRamlQuery(request) || wantsRaml(request)) {
            return raml(request);
        }
        return consoleIndex();
    }

    /** The console page, rendered once from {@code apikit-console/index.html}. */
    @GetMapping("${apikit.base-path:/api}/${apikit.console-path:console}/index.html")
    public ResponseEntity<byte[]> consoleIndex() {
        return ok(renderedIndex(), HTML_CONTENT_TYPE, true);
    }

    /**
     * Any other file of the console tree, typed by its extension. Paths that are not a canonical relative
     * path (an empty, {@code .} or {@code ..} segment, a reverse solidus, or a {@code %} left after decoding)
     * are rejected before the classpath is consulted, so nothing outside {@code apikit-console/} is reachable.
     *
     * <p>Written straight to the servlet response rather than returned as a {@code ResponseEntity}, unlike
     * the other console responses: for a 2xx body written by a message converter, Spring MVC's
     * reflected-file-download guard adds {@code Content-Disposition: inline;filename=f.txt} whenever the
     * path's extension ({@code js}, {@code css}, {@code woff2}, {@code html}) is not matched literally by the
     * mapping pattern, and {@code ConsoleHandler} sent no such header.
     */
    @GetMapping("${apikit.base-path:/api}/${apikit.console-path:console}/**")
    public void consoleFile(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = pathWithinApplication(request);
        String prefix = consolePrefix + "/";
        if (!path.startsWith(prefix)) {
            throw new ApiKitNotFoundException(NOT_FOUND_MESSAGE);
        }
        String rest = path.substring(prefix.length());
        if (!isCanonicalRelativePath(rest) || rest.indexOf('\\') >= 0 || rest.indexOf('%') >= 0) {
            throw new ApiKitNotFoundException(NOT_FOUND_MESSAGE);
        }
        byte[] bytes = consoleFiles.get(rest);
        if (bytes == null) {
            ClassPathResource resource = new ClassPathResource(CONSOLE_ROOT + rest);
            if (!resource.isReadable()) {
                throw new ApiKitNotFoundException(NOT_FOUND_MESSAGE);
            }
            bytes = read(resource);
            consoleFiles.putIfAbsent(rest, bytes);
        }
        response.setStatus(HttpServletResponse.SC_OK);
        // No charset parameter, as for every other console response.
        response.setContentType(contentTypeFor(rest));
        response.setContentLength(bytes.length);
        response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        if ("html".equals(extension(rest))) {
            response.setHeader(HttpHeaders.EXPIRES, "-1");
        }
        response.getOutputStream().write(bytes);
        response.flushBuffer();
    }

    /**
     * A file the RAML includes, requested relative to the console or to the API base path. Registered per
     * include target by {@link #afterSingletonsInstantiated()}, so it carries no mapping annotation.
     */
    public ResponseEntity<byte[]> serveInclude(HttpServletRequest request) {
        String path = pathWithinApplication(request);
        String target = includeTarget(path, consolePrefix + "/");
        if (target == null) {
            target = includeTarget(path, basePath + "/");
        }
        if (target == null) {
            throw new ApiKitNotFoundException(NOT_FOUND_MESSAGE);
        }
        return ok(includes.get(target), contentTypeFor(target), "raml".equals(extension(target)));
    }

    /**
     * Maps {@code GET <console>/<target>} and {@code GET <base>/<target>} to {@link #serveInclude} for every
     * include target. Runs once, after every singleton exists, because the handler mapping cannot be injected
     * into one of its own controllers without an initialisation cycle.
     */
    @Override
    public void afterSingletonsInstantiated() {
        if (!registered.compareAndSet(false, true) || includes.isEmpty()) {
            return;
        }
        String mappingBean = "requestMappingHandlerMapping";
        if (!applicationContext.containsBean(mappingBean)) {
            LOGGER.warn("No {} bean; the {} RAML includes of {} are not served", mappingBean, includes.size(),
                    ramlPath);
            return;
        }
        RequestMappingHandlerMapping mapping =
                applicationContext.getBean(mappingBean, RequestMappingHandlerMapping.class);
        Method handler = serveIncludeMethod();
        for (String target : includes.keySet()) {
            register(mapping, handler, consolePrefix + "/" + target);
            register(mapping, handler, basePath + "/" + target);
        }
    }

    private void register(RequestMappingHandlerMapping mapping, Method handler, String path) {
        RequestMappingInfo info = RequestMappingInfo.paths(path)
                .methods(RequestMethod.GET)
                .options(mapping.getBuilderConfiguration())
                .build();
        try {
            mapping.registerMapping(info, this, handler);
        } catch (IllegalStateException ex) {
            LOGGER.warn("RAML include not served at {}: {}", path, ex.getMessage());
        }
    }

    private static Method serveIncludeMethod() {
        try {
            return ApiKitConsoleController.class.getMethod("serveInclude", HttpServletRequest.class);
        } catch (NoSuchMethodException ex) {
            throw new IllegalStateException("ApiKitConsoleController.serveInclude(HttpServletRequest) is missing", ex);
        }
    }

    private String includeTarget(String path, String prefix) {
        if (!path.startsWith(prefix)) {
            return null;
        }
        String target = path.substring(prefix.length());
        return includes.containsKey(target) ? target : null;
    }

    /** The RAML with its {@code baseUri} set to the URL of the listener that received {@code request}. */
    private ResponseEntity<byte[]> raml(HttpServletRequest request) {
        String listenerUri = request.getScheme() + "://" + hostLiteral(request.getServerName()) + ":"
                + request.getLocalPort() + basePath;
        return ok(withBaseUri(listenerUri).getBytes(StandardCharsets.UTF_8), RAML_MEDIA_TYPE, true);
    }

    /**
     * Replaces the first top-level {@code baseUri:} line with {@code baseUri: <listenerUri>}, or, when there is
     * none, inserts that line after the first top-level {@code title:} line (after the first line without a
     * title). Every other character, {@code !include} lines and their trailing blanks included, is kept.
     */
    String withBaseUri(String listenerUri) {
        String line = "baseUri: " + listenerUri;
        Matcher baseUri = BASE_URI_LINE.matcher(ramlText);
        if (baseUri.find()) {
            return baseUri.replaceFirst(Matcher.quoteReplacement(line));
        }
        Matcher anchor = TITLE_LINE.matcher(ramlText);
        if (!anchor.find()) {
            anchor = FIRST_LINE.matcher(ramlText);
            anchor.find();
        }
        int lineEnd = anchor.end();
        if (lineEnd == ramlText.length()) {
            return ramlText + "\n" + line;
        }
        int breakLength = ramlText.startsWith("\r\n", lineEnd) ? 2 : 1;
        String lineBreak = ramlText.substring(lineEnd, lineEnd + breakLength);
        int insertAt = lineEnd + breakLength;
        return ramlText.substring(0, insertAt) + line + lineBreak + ramlText.substring(insertAt);
    }

    private byte[] renderedIndex() {
        byte[] rendered = renderedIndex;
        if (rendered == null) {
            ClassPathResource resource = new ClassPathResource(CONSOLE_ROOT + INDEX_FILE);
            if (!resource.isReadable()) {
                throw new ApiKitNotFoundException(NOT_FOUND_MESSAGE);
            }
            String html = new String(read(resource), StandardCharsets.UTF_8);
            html = html.replaceFirst(RAML_CONSOLE_SRC, RAML_CONSOLE_SRC_RENDERED);
            html = html.replace(ATTRIBUTES_PLACEHOLDER, CONSOLE_ATTRIBUTES);
            rendered = html.getBytes(StandardCharsets.UTF_8);
            renderedIndex = rendered;
        }
        return rendered;
    }

    /**
     * 200 with the bytes written unchanged under a preset Content-Type, which also skips content negotiation,
     * so no request can turn these responses into a 406. No charset parameter is added, as the handlers sent
     * none.
     */
    private static ResponseEntity<byte[]> ok(byte[] body, String contentType, boolean noCache) {
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(body.length)
                .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        if (noCache) {
            response.header(HttpHeaders.EXPIRES, "-1");
        }
        return response.body(body);
    }

    /**
     * Whether any {@code Accept} header names the RAML media type. Checked here rather than with
     * {@code produces}, which a wildcard {@code Accept} would also satisfy.
     */
    static boolean wantsRaml(HttpServletRequest request) {
        Enumeration<String> accepts = request.getHeaders(HttpHeaders.ACCEPT);
        if (accepts == null) {
            return false;
        }
        while (accepts.hasMoreElements()) {
            String accept = accepts.nextElement();
            if (accept != null && accept.toLowerCase(Locale.ROOT).contains(RAML_MEDIA_TYPE)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the query string names {@code raml}, with or without a value. */
    static boolean hasRamlQuery(HttpServletRequest request) {
        return request.getParameterMap().containsKey("raml");
    }

    /** HEAD is the GET of the same resource without its body, as for every {@code @GetMapping}. */
    private static boolean isGetOrHead(HttpServletRequest request) {
        String method = request.getMethod();
        return "GET".equals(method) || "HEAD".equals(method);
    }

    /** The content type {@code ConsoleHandler} gave a file of this name. */
    static String contentTypeFor(String fileName) {
        return CONTENT_TYPES.getOrDefault(extension(fileName), DEFAULT_CONTENT_TYPE);
    }

    private static String extension(String fileName) {
        String name = fileName.substring(fileName.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** The request path relative to the context path, percent-decoded as UTF-8 ({@code +} stays {@code +}). */
    private static String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            throw new ApiKitNotFoundException(NOT_FOUND_MESSAGE);
        }
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        try {
            return UriUtils.decode(uri, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            // A malformed percent escape names no file.
            throw new ApiKitNotFoundException(NOT_FOUND_MESSAGE);
        }
    }

    /** A non-empty relative path whose every segment is non-empty and neither {@code .} nor {@code ..}. */
    private static boolean isCanonicalRelativePath(String path) {
        if (path.isEmpty()) {
            return false;
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || segment.contains("..")) {
                return false;
            }
        }
        return true;
    }

    /**
     * Collects the RAML's {@code !include} targets in encounter order, skipping comment lines, unsafe targets
     * and targets that are not readable next to the RAML.
     */
    private static Map<String, byte[]> loadIncludes(String raml, String ramlDirectory) {
        Map<String, byte[]> found = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (String line : LINE_BREAK.split(raml)) {
            if (line.stripLeading().startsWith("#")) {
                continue;
            }
            Matcher include = INCLUDE.matcher(line);
            while (include.find()) {
                String target = unquote(include.group(1).strip());
                if (!seen.add(target)) {
                    continue;
                }
                if (target.contains("..") || target.startsWith("/") || target.contains("://")
                        || !SAFE_INCLUDE.matcher(target).matches()) {
                    LOGGER.warn("RAML include '{}' is not served: not a plain relative path", target);
                    continue;
                }
                ClassPathResource resource = new ClassPathResource(ramlDirectory + target);
                if (!resource.isReadable()) {
                    LOGGER.warn("RAML include '{}' is not served: classpath resource {} is not readable", target,
                            ramlDirectory + target);
                    continue;
                }
                found.put(target, read(resource));
            }
        }
        return Collections.unmodifiableMap(found);
    }

    private static String unquote(String target) {
        if (target.length() >= 2) {
            char first = target.charAt(0);
            if ((first == '"' || first == '\'') && target.charAt(target.length() - 1) == first) {
                return target.substring(1, target.length() - 1);
            }
        }
        return target;
    }

    private static byte[] read(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            return StreamUtils.copyToByteArray(in);
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not read " + resource.getDescription(), ex);
        }
    }

    /** Brackets an IPv6 address so it can stand before {@code :port} in a URL. */
    private static String hostLiteral(String host) {
        return host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host;
    }

    private static String stripTrailingSlashes(String path) {
        String stripped = path;
        while (stripped.endsWith("/")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return stripped;
    }

    private static String stripLeadingSlashes(String path) {
        String stripped = path;
        while (stripped.startsWith("/")) {
            stripped = stripped.substring(1);
        }
        return stripped;
    }

    private static String stripSlashes(String path) {
        return stripTrailingSlashes(stripLeadingSlashes(path));
    }
}
