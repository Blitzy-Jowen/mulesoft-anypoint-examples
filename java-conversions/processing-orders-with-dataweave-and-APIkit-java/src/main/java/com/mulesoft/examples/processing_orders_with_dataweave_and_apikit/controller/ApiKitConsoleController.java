/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.controller;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.UnknownHostException;
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

import com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.exception.ApiKitNotFoundException;

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
 *   <li>{@code <base>/console}: 301 to the absolute URL of {@code <base>/console/}, built from the request's
 *       scheme and {@code Host} header and followed by its query string, with an empty body and, unlike the
 *       responses below, no {@code Access-Control-Allow-Origin};</li>
 *   <li>{@code <base>/console/} and {@code <base>/console/index.html}: the console page, its RAML source pointed
 *       at {@code ./?raml} and its attribute placeholder replaced by the handler's options;</li>
 *   <li>{@code <base>/console/<file>}: the console's scripts, styles, fonts and images from the classpath tree
 *       {@code apikit-console/};</li>
 *   <li>{@code GET <base>/console?raml}, {@code GET <base>/console/?raml}, the same paths and {@code GET <base>/}
 *       with an {@code Accept} header naming {@code application/raml+yaml}: the RAML of {@code apikit.raml}
 *       with its {@code baseUri} set to the listener URL, whose host is the {@code Host} header's name only
 *       when that is a legal DNS name, IPv4 address or IPv6 address and the listener socket's local address
 *       otherwise, and whose port is the listener socket's local port on the console paths and the
 *       {@code Host} header's port, none when the header names none, on {@code <base>/};</li>
 *   <li>{@code GET <base>/<include>} and {@code GET <base>/console/<include>}: each file the RAML
 *       {@code !include}s, so the console resolves includes relative to the RAML it loaded.</li>
 * </ul>
 * The console paths answer every method alike, a non-standard one such as {@code PROPFIND} included, because
 * the original listener declares no {@code allowedMethods} and {@code ConsoleHandler} never read the method;
 * only {@code RamlDescriptorHandler} required a GET, so the RAML answers GET alone on {@code <base>/} and GET
 * (and HEAD) alone on the console paths, and any other method on {@code <base>/console} or
 * {@code <base>/console/} gets the redirect or the page. The console paths are mapped without a method, which
 * Spring matches for every method but a plain OPTIONS request, so each also has an explicit OPTIONS mapping
 * that gives that request the same response.
 *
 * <p>Every miss throws {@link ApiKitNotFoundException}, which SC-3 answers with APIkit's 404 body. The class
 * maps no pattern broader than these, so unknown resources and methods under the base path still reach the
 * APIkit 404 and 405 mappings of SC-3.
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
 *   <li>a {@code Host} header naming no legal DNS name, IPv4 address or IPv6 address gives the redirect's
 *       {@code Location} and the RAML's {@code baseUri} the local address and local port of the listener
 *       socket, and one whose legal name is followed by anything other than nothing, a bare {@code :} or
 *       {@code :} and a port number up to 65535 gives them that name with the local port, while APIkit copied
 *       what the {@code Host} header carried into them unchanged;</li>
 *   <li>a HEAD request on the console paths is answered as the GET without its body, the RAML included,
 *       while {@code RamlDescriptorHandler} served the RAML to GET alone, so APIkit answered such a HEAD with
 *       the redirect or the console page.</li>
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

    /** One label of a host name: 1 to 63 letters, digits or {@code _}, with {@code -} only between them. */
    private static final Pattern HOST_LABEL = Pattern.compile("[A-Za-z0-9_](?:[A-Za-z0-9_-]{0,61}[A-Za-z0-9_])?");

    /** Characters of an IPv6 address without brackets or zone, IPv4-suffixed forms included. */
    private static final Pattern IPV6_CHARACTERS = Pattern.compile("[0-9A-Fa-f:.]+");

    /** Longest host name, without the optional trailing {@code .} of an absolute name. */
    private static final int MAX_HOST_NAME_LENGTH = 253;

    /** The port part of a {@code Host} header: {@code :} and 1 to 5 ASCII digits. */
    private static final Pattern HOST_HEADER_PORT = Pattern.compile(":[0-9]{1,5}");

    /** Largest TCP port number. */
    private static final int MAX_PORT = 65535;

    /** Host of the RAML's {@code baseUri} when neither the {@code Host} header nor the socket names a legal one. */
    private static final String LOOPBACK_HOST = "127.0.0.1";

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
     * {@code RamlDescriptorHandler} on the API root: the RAML of {@link #routerRaml} to a GET whose
     * {@code Accept} header names {@code application/raml+yaml}. Every other request to {@code <base>/}, a HEAD
     * included, is the unknown resource it was for APIkit, whose handler took GET alone and left every other
     * method to the router.
     *
     * <p>Mapped without a method, so that every method but OPTIONS, a non-standard one such as
     * {@code PROPFIND} included, reaches it and a non-RAML request answers APIkit's 404 rather than a 405;
     * Spring never matches a plain OPTIONS request to a mapping without a method, so {@link #apiRootOptions}
     * answers that one. The trailing {@code /} is significant: Spring matches no optional trailing slash, so
     * neither {@code <base>} nor any resource below it reaches this method.
     */
    @RequestMapping("${apikit.base-path:/api}/")
    public ResponseEntity<byte[]> apiRoot(HttpServletRequest request) {
        if (isGet(request) && wantsRaml(request)) {
            return routerRaml(request);
        }
        throw new ApiKitNotFoundException(NOT_FOUND_MESSAGE);
    }

    /**
     * OPTIONS on the API root, the unknown resource APIkit answered with its 404 like every other non-RAML
     * request to {@code <base>/}. The explicit mapping is needed because Spring leaves a plain OPTIONS request
     * out of a mapping that declares no method, such as {@link #apiRoot}, and would otherwise answer it itself
     * with 200 and an {@code Allow} header.
     *
     * @return never
     * @throws ApiKitNotFoundException always, which SC-3 answers with APIkit's 404 body
     */
    @RequestMapping(value = "${apikit.base-path:/api}/", method = RequestMethod.OPTIONS)
    public ResponseEntity<byte[]> apiRootOptions() {
        throw new ApiKitNotFoundException(NOT_FOUND_MESSAGE);
    }

    /**
     * The console path without its trailing slash, for every method: the RAML when a GET (or HEAD) asks for
     * it, otherwise a 301 whose {@code Location} is the absolute URL {@code ConsoleHandler} wrote: the request's
     * scheme, the {@link #requestAuthority authority} the {@code Host} header names, the servlet context path,
     * {@code <base>/console/} and the query string when there is one. The path is built from the configured
     * console path alone and the authority from a validated host and port, so neither the raw request path nor
     * any other byte of the {@code Host} header reaches the header. The 301 sends that {@code Location} with an
     * empty body and without {@code Access-Control-Allow-Origin}, because {@code ConsoleHandler} returned from
     * its redirect before setting that header, which only its successful console responses carry.
     *
     * <p>Written to the servlet response and committed before the method returns, because the 301 has no
     * body to commit it: the servlet fills a response still open after an OPTIONS request with an
     * {@code Allow} header and one after a TRACE request with a {@code message/http} echo of the request, and
     * {@code ConsoleHandler} sent neither.
     *
     * @param request the request, whose method, {@code Accept} header and query string select the response
     * @param response the response the RAML or the 301 is written to
     * @throws IOException if the response cannot be written
     */
    @RequestMapping("${apikit.base-path:/api}/${apikit.console-path:console}")
    public void consoleNoSlash(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (isGetOrHead(request) && (hasRamlQuery(request) || wantsRaml(request))) {
            send(raml(request), response);
            return;
        }
        String contextPath = request.getContextPath();
        StringBuilder location = new StringBuilder(request.getScheme()).append("://")
                .append(requestAuthority(request))
                .append(contextPath == null ? "" : contextPath)
                .append(UriUtils.encodePath(consolePrefix, StandardCharsets.UTF_8)).append('/');
        String query = request.getQueryString();
        if (query != null && !query.isEmpty()) {
            location.append('?').append(query);
        }
        send(ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
                .header(HttpHeaders.LOCATION, location.toString())
                .contentLength(0)
                .build(), response);
    }

    /**
     * OPTIONS on the console path without its trailing slash, answered as {@link #consoleNoSlash} answers every
     * method other than GET or HEAD. The explicit mapping is needed because Spring leaves a plain OPTIONS
     * request out of a mapping that declares no method and would otherwise answer it itself with 200 and an
     * {@code Allow} header.
     *
     * @param request the OPTIONS request
     * @param response the response the 301 is written to
     * @throws IOException if the response cannot be written
     */
    @RequestMapping(value = "${apikit.base-path:/api}/${apikit.console-path:console}",
            method = RequestMethod.OPTIONS)
    public void consoleNoSlashOptions(HttpServletRequest request, HttpServletResponse response) throws IOException {
        consoleNoSlash(request, response);
    }

    /**
     * The console root, for every method: the RAML when a GET (or HEAD) asks for it, otherwise the console page.
     *
     * @param request the request, whose method, {@code Accept} header and query string select the response
     * @return the RAML or the console page
     */
    @RequestMapping("${apikit.base-path:/api}/${apikit.console-path:console}/")
    public ResponseEntity<byte[]> consoleRoot(HttpServletRequest request) {
        if (isGetOrHead(request) && (hasRamlQuery(request) || wantsRaml(request))) {
            return raml(request);
        }
        return consoleIndex();
    }

    /**
     * OPTIONS on the console root: the console page, as {@link #consoleRoot} gives every method other than GET
     * or HEAD, written to the servlet response and committed so that the servlet adds no {@code Allow} header.
     * The explicit mapping is needed because Spring leaves a plain OPTIONS request out of a mapping that
     * declares no method.
     *
     * @param request the OPTIONS request
     * @param response the response the console page is written to
     * @throws IOException if the response cannot be written
     */
    @RequestMapping(value = "${apikit.base-path:/api}/${apikit.console-path:console}/",
            method = RequestMethod.OPTIONS)
    public void consoleRootOptions(HttpServletRequest request, HttpServletResponse response) throws IOException {
        send(consoleRoot(request), response);
    }

    /**
     * The console page, rendered once from {@code apikit-console/index.html}, for every method.
     *
     * @return the console page
     */
    @RequestMapping("${apikit.base-path:/api}/${apikit.console-path:console}/index.html")
    public ResponseEntity<byte[]> consoleIndex() {
        return ok(renderedIndex(), HTML_CONTENT_TYPE, true);
    }

    /**
     * OPTIONS on the console page, written to the servlet response and committed so that the servlet adds no
     * {@code Allow} header. The explicit mapping is needed because Spring leaves a plain OPTIONS request out of
     * a mapping that declares no method, such as {@link #consoleIndex}.
     *
     * @param response the response the console page is written to
     * @throws IOException if the response cannot be written
     */
    @RequestMapping(value = "${apikit.base-path:/api}/${apikit.console-path:console}/index.html",
            method = RequestMethod.OPTIONS)
    public void consoleIndexOptions(HttpServletResponse response) throws IOException {
        send(consoleIndex(), response);
    }

    /**
     * Any other file of the console tree, typed by its extension, for every method. Paths that are not a
     * canonical relative path (an empty, {@code .} or {@code ..} segment, a reverse solidus, or a {@code %}
     * left after decoding) are rejected before the classpath is consulted, so nothing outside
     * {@code apikit-console/} is reachable. A RAML include requested below the console path with a method
     * other than GET or HEAD lands here too and, being no console file, gets the 404.
     *
     * <p>Written straight to the servlet response rather than returned as a {@code ResponseEntity}, unlike
     * the other console responses: for a 2xx body written by a message converter, Spring MVC's
     * reflected-file-download guard adds {@code Content-Disposition: inline;filename=f.txt} whenever the
     * path's extension ({@code js}, {@code css}, {@code woff2}, {@code html}) is not matched literally by the
     * mapping pattern, and {@code ConsoleHandler} sent no such header. The response is committed before the
     * method returns, so the servlet adds nothing to it after an OPTIONS or TRACE request.
     *
     * @param request the request naming the file
     * @param response the response the file is written to
     * @throws IOException if the response cannot be written
     */
    @RequestMapping("${apikit.base-path:/api}/${apikit.console-path:console}/**")
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
     * OPTIONS on a file of the console tree, answered as {@link #consoleFile} answers every other method. The
     * explicit mapping is needed because Spring leaves a plain OPTIONS request out of a mapping that declares no
     * method and would otherwise answer it itself with 200 and an {@code Allow} header.
     *
     * @param request the OPTIONS request naming the file
     * @param response the response the file is written to
     * @throws IOException if the response cannot be written
     */
    @RequestMapping(value = "${apikit.base-path:/api}/${apikit.console-path:console}/**",
            method = RequestMethod.OPTIONS)
    public void consoleFileOptions(HttpServletRequest request, HttpServletResponse response) throws IOException {
        consoleFile(request, response);
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

    /**
     * The RAML of the console paths, with its {@code baseUri} set to the URL of the listener that received
     * {@code request} as {@code getApikitRamlConsole} built it: its scheme, a host, its local port and the base
     * path. The host is the name the {@code Host} header carries only when that is a legal DNS name, IPv4
     * address or IPv6 address; any other name, an absent one or a {@code Host} header the server cannot parse
     * falls back to the local address of the listener socket, so no other header byte reaches the unquoted YAML
     * scalar.
     */
    private ResponseEntity<byte[]> raml(HttpServletRequest request) {
        String listenerUri = request.getScheme() + "://" + hostLiteral(listenerHost(request)) + ":"
                + request.getLocalPort() + basePath;
        return ramlWithBaseUri(listenerUri);
    }

    /**
     * The RAML of the API root, with its {@code baseUri} set to the URL {@code getApikitRaml} built: the
     * request's scheme, the {@link #requestAuthority authority} the {@code Host} header names, its port
     * included and none added when it names none, and the base path.
     */
    private ResponseEntity<byte[]> routerRaml(HttpServletRequest request) {
        return ramlWithBaseUri(request.getScheme() + "://" + requestAuthority(request) + basePath);
    }

    /** The RAML with its {@code baseUri} set to {@code listenerUri}, as {@code application/raml+yaml}. */
    private ResponseEntity<byte[]> ramlWithBaseUri(String listenerUri) {
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
     * Writes {@code entity}'s status, headers and body bytes to {@code response} and commits it, so nothing the
     * servlet adds once the handler has returned reaches the client. Content-Type and Content-Length go through
     * their own setters, so the type carries no charset parameter and the container frames the body by that
     * length, as for the responses Spring writes.
     */
    private static void send(ResponseEntity<byte[]> entity, HttpServletResponse response) throws IOException {
        HttpHeaders headers = entity.getHeaders();
        response.setStatus(entity.getStatusCode().value());
        headers.forEach((name, values) -> {
            if (!HttpHeaders.CONTENT_TYPE.equalsIgnoreCase(name)
                    && !HttpHeaders.CONTENT_LENGTH.equalsIgnoreCase(name)) {
                values.forEach(value -> response.addHeader(name, value));
            }
        });
        MediaType contentType = headers.getContentType();
        if (contentType != null) {
            response.setContentType(contentType.toString());
        }
        byte[] body = entity.getBody() == null ? new byte[0] : entity.getBody();
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        response.flushBuffer();
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

    /**
     * {@code RamlDescriptorHandler}'s method test on the API root: the method, upper-cased, is GET, so HEAD is
     * not.
     */
    private static boolean isGet(HttpServletRequest request) {
        String method = request.getMethod();
        return method != null && "GET".equals(method.toUpperCase(Locale.ROOT));
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

    /**
     * The host of the listener URL: the {@code Host} header's name, without its port or brackets, when
     * {@link #isLegalHost} accepts it; otherwise the local address of the socket that accepted the connection
     * without its zone, or {@link #LOOPBACK_HOST} should that be unavailable too. The rejected name is not
     * logged, so header bytes reach neither the RAML nor the log.
     */
    private static String listenerHost(HttpServletRequest request) {
        String serverName;
        try {
            serverName = request.getServerName();
        } catch (RuntimeException ex) {
            // Undertow cannot strip the brackets of a Host header with an unclosed '[', such as "[abc".
            serverName = null;
        }
        if (isLegalHost(serverName)) {
            return serverName;
        }
        LOGGER.debug("Host header names no legal host; the RAML baseUri uses the listener's local address");
        String localAddress = request.getLocalAddr();
        if (localAddress != null && localAddress.indexOf('%') >= 0) {
            localAddress = localAddress.substring(0, localAddress.indexOf('%'));
        }
        return isLegalHost(localAddress) ? localAddress : LOOPBACK_HOST;
    }

    /**
     * The authority of the request URL {@code ConsoleHandler} and {@code getApikitRaml} copied from the
     * {@code Host} header: the {@link #listenerHost host}, bracketed when it is an IPv6 address, then the
     * header's port. The header is split as Undertow splits it for {@link HttpServletRequest#getServerName}: a
     * bracketed name ends at the first {@code ]}, any other at the first {@code :}. After a legal name, nothing or
     * a bare {@code :} adds no port, as an empty port means the scheme's default, and {@code :} with 1 to 5
     * digits up to {@link #MAX_PORT} adds that port; an absent or blank header, an illegal name, an unclosed
     * {@code [} and anything else after the name add the listener socket's local port instead, so no header
     * byte but a legal name and a port number reaches the URL.
     */
    private static String requestAuthority(HttpServletRequest request) {
        String host = hostLiteral(listenerHost(request));
        String localPort = ":" + request.getLocalPort();
        String header = request.getHeader(HttpHeaders.HOST);
        if (header == null || header.isBlank()) {
            return host + localPort;
        }
        String name;
        String port;
        if (header.startsWith("[")) {
            int close = header.indexOf(']');
            if (close < 0) {
                return host + localPort;
            }
            name = header.substring(1, close);
            port = header.substring(close + 1);
        } else {
            int colon = header.indexOf(':');
            name = colon < 0 ? header : header.substring(0, colon);
            port = colon < 0 ? "" : header.substring(colon);
        }
        if (!isLegalHost(name)) {
            return host + localPort;
        }
        if (port.isEmpty() || ":".equals(port)) {
            return host;
        }
        if (HOST_HEADER_PORT.matcher(port).matches() && Integer.parseInt(port.substring(1)) <= MAX_PORT) {
            return host + port;
        }
        return host + localPort;
    }

    /**
     * Whether {@code host} is a legal host: a DNS name, dotted IPv4 addresses included, of dot-separated
     * {@link #HOST_LABEL labels} and at most {@link #MAX_HOST_NAME_LENGTH} characters besides one optional
     * trailing {@code .}, or an IPv6 address without brackets or zone.
     */
    private static boolean isLegalHost(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        if (host.indexOf(':') >= 0) {
            return isIpv6Address(host);
        }
        String name = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        if (name.isEmpty() || name.length() > MAX_HOST_NAME_LENGTH) {
            return false;
        }
        for (String label : name.split("\\.", -1)) {
            if (!HOST_LABEL.matcher(label).matches()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether {@code host} is an IPv6 address, checked by the JDK's literal parser without any name resolution:
     * only hexadecimal digits, {@code :} and {@code .} with at least one {@code :} get that far, and in brackets
     * {@link InetAddress#getByName} accepts nothing but an IPv6 literal, throwing for any other such text
     * instead of looking it up.
     */
    private static boolean isIpv6Address(String host) {
        if (host.indexOf(':') < 0 || !IPV6_CHARACTERS.matcher(host).matches()) {
            return false;
        }
        try {
            InetAddress.getByName("[" + host + "]");
            return true;
        } catch (UnknownHostException ex) {
            return false;
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
