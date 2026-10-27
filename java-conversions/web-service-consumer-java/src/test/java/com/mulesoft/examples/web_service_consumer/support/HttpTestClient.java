package com.mulesoft.examples.web_service_consumer.support;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/**
 * HTTP/1.1 test client over a raw {@link Socket}: shared test component ST-1 of the conversion plan (AAP 0.4.4).
 *
 * <p>The converted applications run on Undertow so that standard and custom reason phrases
 * ({@code HTTP/1.1 400 Invalid input data}) reach the wire, while the JDK's built-in {@code HttpClient} exposes
 * the status code only and hides the reason phrase. This client therefore writes the request bytes itself and
 * returns the raw status line (version, code, reason phrase), the response headers in arrival order with
 * duplicates kept, and the body bytes.
 *
 * <p>Contract relied on by every consumer:
 * <ul>
 *   <li>It is JDK-only and project-neutral: every project with an HTTP listener carries a copy that differs from
 *       this one in the {@code package} line alone, so nothing here may reference Spring, JUnit, AssertJ or any
 *       project type.</li>
 *   <li>The request target is sent exactly as given: no normalization, no percent-encoding or decoding, no
 *       {@code ..} resolution and no leading {@code /} added, so path-traversal probes reach the server as raw
 *       bytes. A query string is part of the path.</li>
 *   <li>Only {@code Host}, {@code Content-Length} (when a body is sent) and {@code Connection: close} are added;
 *       no default {@code Accept}, {@code Content-Type}, {@code User-Agent}, {@code Accept-Encoding} or
 *       {@code Expect}, so tests control content negotiation completely.</li>
 *   <li>Redirects are not followed and bodies are not decompressed.</li>
 *   <li>Every call opens and closes its own connection; instances are immutable and safe to share between
 *       threads.</li>
 *   <li>Connecting times out after {@link #TIMEOUT_MILLIS}. Each read from the connection, the TLS handshake's
 *       included, times out after the client's read budget: {@link #TIMEOUT_MILLIS} unless {@link #withReadTimeout}
 *       made a copy with another one, for a server that itself waits about that long before it answers.</li>
 *   <li>HTTPS mode ({@link #https}) trusts only the supplied trust store and performs no hostname verification,
 *       because the carried demo certificates are not issued for {@code localhost}.</li>
 * </ul>
 */
public final class HttpTestClient {

    /** Connect timeout, and the socket read timeout unless {@link #withReadTimeout} sets another, in milliseconds. */
    public static final int TIMEOUT_MILLIS = 10_000;

    private static final byte[] CRLF = {'\r', '\n'};

    /** Largest response body buffered in memory: the largest byte array a JVM reliably allocates. */
    private static final int MAX_BODY_LENGTH = Integer.MAX_VALUE - 8;

    private final String host;
    private final int port;
    /** {@code null} in plain HTTP mode. */
    private final SSLSocketFactory sslSocketFactory;
    /** The socket read timeout of every call, in milliseconds; positive. */
    private final int readTimeoutMillis;

    /**
     * Creates a plain HTTP client for {@code host:port}.
     *
     * @throws IllegalArgumentException if {@code host} is null or blank, or {@code port} is outside 1..65535
     */
    public HttpTestClient(String host, int port) {
        this(host, port, null, TIMEOUT_MILLIS);
    }

    private HttpTestClient(String host, int port, SSLSocketFactory sslSocketFactory, int readTimeoutMillis) {
        checkEndpoint(host, port);
        this.host = host;
        this.port = port;
        this.sslSocketFactory = sslSocketFactory;
        this.readTimeoutMillis = readTimeoutMillis;
    }

    /** Plain HTTP client for {@code localhost:port}. */
    public static HttpTestClient localhost(int port) {
        return new HttpTestClient("localhost", port);
    }

    /**
     * HTTPS client whose TLS context trusts only the certificates of the given trust store.
     *
     * <p>Each store path is tried first as a file-system path, then as a classpath resource.
     *
     * @param trustStoreType     key store type, for example {@code JKS} or {@code PKCS12}
     * @param trustStorePassword store password, or {@code null} to load the store without integrity check
     * @throws IllegalArgumentException if an argument is invalid or the store cannot be found
     * @throws IllegalStateException    if the store cannot be read or the TLS context cannot be built
     */
    public static HttpTestClient https(String host, int port, String trustStorePath, String trustStoreType,
            String trustStorePassword) {
        return https(host, port, trustStorePath, trustStoreType, trustStorePassword, null, null, null);
    }

    /**
     * HTTPS client whose TLS context trusts only the given trust store and, when {@code keyStorePath} is not
     * {@code null}, presents the client key of the given key store.
     *
     * <p>Each store path is tried first as a file-system path, then as a classpath resource.
     *
     * @param keyStorePath     client key store, or {@code null} for no client key
     * @param keyStorePassword password of the key store and of its key entries
     * @throws IllegalArgumentException if an argument is invalid or a store cannot be found
     * @throws IllegalStateException    if a store cannot be read or the TLS context cannot be built
     */
    public static HttpTestClient https(String host, int port, String trustStorePath, String trustStoreType,
            String trustStorePassword, String keyStorePath, String keyStoreType, String keyStorePassword) {
        checkEndpoint(host, port);
        KeyStore trustStore = loadStore(trustStorePath, trustStoreType, trustStorePassword);
        KeyManager[] keyManagers = null;
        if (keyStorePath != null) {
            KeyStore keyStore = loadStore(keyStorePath, keyStoreType, keyStorePassword);
            try {
                KeyManagerFactory keyManagerFactory =
                        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                keyManagerFactory.init(keyStore, keyStorePassword == null ? null : keyStorePassword.toCharArray());
                keyManagers = keyManagerFactory.getKeyManagers();
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(
                        "Cannot initialise key managers from key store " + keyStorePath + ": " + e.getMessage(), e);
            }
        }
        try {
            TrustManagerFactory trustManagerFactory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(trustStore);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keyManagers, trustManagerFactory.getTrustManagers(), new SecureRandom());
            return new HttpTestClient(host, port, context.getSocketFactory(), TIMEOUT_MILLIS);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(
                    "Cannot build TLS context from trust store " + trustStorePath + ": " + e.getMessage(), e);
        }
    }

    /**
     * A copy of this client for the same endpoint, in the same mode and with the same TLS context, whose every read
     * from the connection, the TLS handshake's included, times out after {@code readTimeoutMillis} instead of this
     * client's budget. It is meant for a server that itself waits about {@link #TIMEOUT_MILLIS} or longer before it
     * answers. Connecting still times out after {@link #TIMEOUT_MILLIS}, and this client is unchanged.
     *
     * @param readTimeoutMillis the longest wait for any one read, in milliseconds
     * @return the copy
     * @throws IllegalArgumentException if {@code readTimeoutMillis} is not positive
     */
    public HttpTestClient withReadTimeout(int readTimeoutMillis) {
        if (readTimeoutMillis <= 0) {
            throw new IllegalArgumentException(
                    "read timeout must be a positive number of milliseconds: " + readTimeoutMillis);
        }
        return new HttpTestClient(host, port, sslSocketFactory, readTimeoutMillis);
    }

    /** The longest wait for any one read from the connection, in milliseconds; {@link #TIMEOUT_MILLIS} by default. */
    public int readTimeoutMillis() {
        return readTimeoutMillis;
    }

    /**
     * {@code http://host:port} or {@code https://host:port}, without a trailing slash, for comparing
     * {@code Location} headers.
     */
    public String baseUrl() {
        return (sslSocketFactory == null ? "http://" : "https://") + authority();
    }

    /**
     * Sends one request on a new connection and reads the complete response.
     *
     * <p>Head order: the request line {@code METHOD path HTTP/1.1}; {@code Host: host:port}, or the caller's
     * {@code Host} value(s) in its place; every other caller header in the map's iteration order with the
     * caller's name casing; {@code Content-Length} when a body is sent and the caller supplied none;
     * {@code Connection: close} unless the caller supplied a {@code Connection} header. Pass a
     * {@link LinkedHashMap} to control header order. A map carries one value per name; to send a name on several
     * header lines, as a recorded request may, use {@link #sendHeaderPairs}.
     *
     * @param method  any HTTP method token ({@code GET}, {@code PATCH}, {@code PROPFIND}, ...), sent verbatim
     * @param path    request target sent byte-for-byte as given, query string included
     * @param headers request headers, or {@code null} for none
     * @param body    {@code null} sends no body and no {@code Content-Length}; any other array, a zero-length one
     *                included, is sent with {@code Content-Length: <length>}
     * @throws IllegalArgumentException if the method, path or a header is not valid on the wire
     * @throws UncheckedIOException     if connecting, the TLS handshake, writing or reading fails; the message
     *                                  names the request line and the endpoint
     */
    public Response send(String method, String path, Map<String, String> headers, byte[] body) {
        return sendHeaderPairs(method, path, headers == null ? null : new ArrayList<>(headers.entrySet()), body);
    }

    /**
     * Sends one request whose headers are ordered name/value pairs on a new connection and reads the complete
     * response; fixture replay uses it to send a recorded request's header lines as recorded.
     *
     * <p>Each pair is written as its own header line {@code name: value} with the caller's name casing, in list
     * order; a name given in several pairs, such as two {@code Cookie} pairs, is sent as that many separate lines
     * and never joined. Head order is otherwise that of {@link #send}: the request line
     * {@code METHOD path HTTP/1.1}; {@code Host: host:port}, or every caller {@code Host} pair in list order in its
     * place; every other pair in list order; {@code Content-Length} when a body is sent and no pair supplies one;
     * {@code Connection: close} unless a pair supplies {@code Connection}.
     *
     * @param method  any HTTP method token ({@code GET}, {@code PATCH}, {@code PROPFIND}, ...), sent verbatim
     * @param path    request target sent byte-for-byte as given, query string included
     * @param headers request header pairs in sending order, or {@code null} for none
     * @param body    {@code null} sends no body and no {@code Content-Length}; any other array, a zero-length one
     *                included, is sent with {@code Content-Length: <length>}
     * @return the complete response
     * @throws IllegalArgumentException if the method, path or a header is not valid on the wire, or a pair is
     *                                  {@code null}
     * @throws UncheckedIOException     if connecting, the TLS handshake, writing or reading fails; the message
     *                                  names the request line and the endpoint
     */
    public Response sendHeaderPairs(String method, String path, List<Map.Entry<String, String>> headers,
            byte[] body) {
        checkMethod(method);
        checkPath(path);
        byte[] head = requestHead(method, path, headers, body);
        try {
            return execute(method, head, body);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    method + " " + path + " HTTP/1.1 to " + host + ":" + port + " failed: " + e.getMessage(), e);
        }
    }

    /** {@code GET} with no headers beyond those {@link #send} always adds, and no body. */
    public Response get(String path) {
        return send("GET", path, null, null);
    }

    /** {@code GET} with the given headers and no body. */
    public Response get(String path, Map<String, String> headers) {
        return send("GET", path, headers, null);
    }

    /**
     * {@code POST} of a UTF-8 text body.
     *
     * @param contentType sent first as {@code Content-Type}; {@code null} sends no {@code Content-Type}
     * @param body        {@code null} sends no body; {@code ""} sends {@code Content-Length: 0}
     */
    public Response post(String path, String contentType, String body) {
        return post(path, contentType, body, null);
    }

    /**
     * {@code POST} of a UTF-8 text body; the extra headers follow {@code Content-Type}.
     *
     * @param contentType sent first as {@code Content-Type}; {@code null} sends no {@code Content-Type}
     * @param body        {@code null} sends no body; {@code ""} sends {@code Content-Length: 0}
     * @param headers     extra headers, or {@code null}
     */
    public Response post(String path, String contentType, String body, Map<String, String> headers) {
        return sendText("POST", path, contentType, body, headers);
    }

    /**
     * {@code PUT} of a UTF-8 text body.
     *
     * @param contentType sent first as {@code Content-Type}; {@code null} sends no {@code Content-Type}
     * @param body        {@code null} sends no body; {@code ""} sends {@code Content-Length: 0}
     */
    public Response put(String path, String contentType, String body) {
        return put(path, contentType, body, null);
    }

    /**
     * {@code PUT} of a UTF-8 text body; the extra headers follow {@code Content-Type}.
     *
     * @param contentType sent first as {@code Content-Type}; {@code null} sends no {@code Content-Type}
     * @param body        {@code null} sends no body; {@code ""} sends {@code Content-Length: 0}
     * @param headers     extra headers, or {@code null}
     */
    public Response put(String path, String contentType, String body, Map<String, String> headers) {
        return sendText("PUT", path, contentType, body, headers);
    }

    /** {@code DELETE} with no body and no {@code Content-Length}. */
    public Response delete(String path) {
        return send("DELETE", path, null, null);
    }

    /**
     * {@code DELETE} with a UTF-8 text body.
     *
     * @param contentType sent first as {@code Content-Type}; {@code null} sends no {@code Content-Type}
     * @param body        {@code null} sends no body; {@code ""} sends {@code Content-Length: 0}
     */
    public Response delete(String path, String contentType, String body) {
        return sendText("DELETE", path, contentType, body, null);
    }

    private Response sendText(String method, String path, String contentType, String body,
            Map<String, String> extraHeaders) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (contentType != null) {
            headers.put("Content-Type", contentType);
        }
        if (extraHeaders != null) {
            headers.putAll(extraHeaders);
        }
        return send(method, path, headers, body == null ? null : body.getBytes(StandardCharsets.UTF_8));
    }

    private static void checkMethod(String method) {
        if (method == null || method.isEmpty()) {
            throw new IllegalArgumentException("method must not be null or empty");
        }
        for (int i = 0; i < method.length(); i++) {
            char c = method.charAt(i);
            if (c <= ' ' || c > '~') {
                throw new IllegalArgumentException("method must be a token of visible ASCII characters: " + method);
            }
        }
    }

    private static void checkPath(String path) {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("path must not be null or empty");
        }
        if (path.indexOf('\r') >= 0 || path.indexOf('\n') >= 0 || path.indexOf(' ') >= 0) {
            throw new IllegalArgumentException("path must not contain CR, LF or space: " + path);
        }
    }

    private static void checkHeader(String name, String value) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("header name must not be null or empty");
        }
        if (value == null) {
            throw new IllegalArgumentException("value of header " + name + " must not be null");
        }
        if (name.indexOf('\r') >= 0 || name.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("header must not contain CR or LF: " + name);
        }
    }

    private byte[] requestHead(String method, String path, List<Map.Entry<String, String>> headers, byte[] body) {
        List<Map.Entry<String, String>> hostHeaders = new ArrayList<>();
        List<Map.Entry<String, String>> otherHeaders = new ArrayList<>();
        boolean contentLengthSupplied = false;
        boolean connectionSupplied = false;
        if (headers != null) {
            for (Map.Entry<String, String> header : headers) {
                if (header == null) {
                    throw new IllegalArgumentException("header pairs must not contain null");
                }
                String name = header.getKey();
                String value = header.getValue();
                checkHeader(name, value);
                if (name.equalsIgnoreCase("Host")) {
                    hostHeaders.add(Map.entry(name, value));
                    continue;
                }
                contentLengthSupplied |= name.equalsIgnoreCase("Content-Length");
                connectionSupplied |= name.equalsIgnoreCase("Connection");
                otherHeaders.add(Map.entry(name, value));
            }
        }

        ByteArrayOutputStream head = new ByteArrayOutputStream(256);
        writeLine(head, method + " " + path + " HTTP/1.1");
        if (hostHeaders.isEmpty()) {
            writeLine(head, "Host: " + authority());
        } else {
            for (Map.Entry<String, String> header : hostHeaders) {
                writeLine(head, header.getKey() + ": " + header.getValue());
            }
        }
        for (Map.Entry<String, String> header : otherHeaders) {
            writeLine(head, header.getKey() + ": " + header.getValue());
        }
        if (body != null && !contentLengthSupplied) {
            writeLine(head, "Content-Length: " + body.length);
        }
        if (!connectionSupplied) {
            writeLine(head, "Connection: close");
        }
        head.writeBytes(CRLF);
        return head.toByteArray();
    }

    private static void writeLine(ByteArrayOutputStream out, String line) {
        out.writeBytes(line.getBytes(StandardCharsets.UTF_8));
        out.writeBytes(CRLF);
    }

    private static void checkEndpoint(String host, int port) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host must not be null or blank");
        }
        if (host.indexOf('\r') >= 0 || host.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("host must not contain CR or LF: " + host);
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be in 1..65535: " + port);
        }
    }

    private static KeyStore loadStore(String path, String type, String password) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Key/trust store path must not be null or blank");
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("Key/trust store type must not be null or blank: " + path);
        }
        try (InputStream in = openStore(path)) {
            KeyStore store = KeyStore.getInstance(type);
            store.load(in, password == null ? null : password.toCharArray());
            return store;
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("Cannot load key/trust store " + path + ": " + e.getMessage(), e);
        }
    }

    private static InputStream openStore(String path) throws IOException {
        if (isRegularFile(path)) {
            return Files.newInputStream(Path.of(path));
        }
        String resource = path.startsWith("/") ? path.substring(1) : path;
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        InputStream in = contextLoader == null ? null : contextLoader.getResourceAsStream(resource);
        if (in == null) {
            in = HttpTestClient.class.getResourceAsStream("/" + resource);
        }
        if (in == null) {
            throw new IllegalArgumentException("Key/trust store not found on filesystem or classpath: " + path);
        }
        return in;
    }

    private static boolean isRegularFile(String path) {
        try {
            return Files.isRegularFile(Path.of(path));
        } catch (InvalidPathException e) {
            // Not a valid file-system path (for example a classpath-style name); the classpath is tried next.
            return false;
        }
    }

    /** {@code host:port}, with an IPv6 literal bracketed as the Host header and URLs require. */
    private String authority() {
        boolean ipv6Literal = host.indexOf(':') >= 0 && !host.startsWith("[");
        return (ipv6Literal ? "[" + host + "]" : host) + ":" + port;
    }

    private Response execute(String method, byte[] head, byte[] body) throws IOException {
        try (Socket plain = new Socket()) {
            plain.connect(new InetSocketAddress(host, port), TIMEOUT_MILLIS);
            plain.setSoTimeout(readTimeoutMillis);
            if (sslSocketFactory == null) {
                return exchange(plain, method, head, body);
            }
            // Endpoint identification stays off (the raw SSLSocket default): the demo certificates do not name
            // the test host, and trust is still restricted to the supplied trust store.
            try (SSLSocket tls = (SSLSocket) sslSocketFactory.createSocket(plain, host, port, true)) {
                tls.setUseClientMode(true);
                tls.setSoTimeout(readTimeoutMillis);
                tls.startHandshake();
                return exchange(tls, method, head, body);
            }
        }
    }

    private static Response exchange(Socket socket, String method, byte[] head, byte[] body) throws IOException {
        IOException writeFailure = null;
        try {
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            out.write(head);
            if (body != null) {
                out.write(body);
            }
            out.flush();
        } catch (IOException e) {
            // A server may answer and close before consuming the whole body; its response is still readable.
            writeFailure = e;
        }
        try {
            return readResponse(new BufferedInputStream(socket.getInputStream()), method);
        } catch (IOException readFailure) {
            if (writeFailure != null) {
                writeFailure.addSuppressed(readFailure);
                throw writeFailure;
            }
            throw readFailure;
        }
    }

    private static Response readResponse(InputStream in, String method) throws IOException {
        while (true) {
            String statusLine = readLine(in);
            if (statusLine == null) {
                throw new IOException("connection closed before status line");
            }
            int firstSpace = statusLine.indexOf(' ');
            if (firstSpace <= 0) {
                throw new IOException("malformed status line: " + statusLine);
            }
            int secondSpace = statusLine.indexOf(' ', firstSpace + 1);
            String code = secondSpace < 0
                    ? statusLine.substring(firstSpace + 1)
                    : statusLine.substring(firstSpace + 1, secondSpace);
            int status = parseStatus(code, statusLine);
            List<Map.Entry<String, String>> headers = readHeaders(in);
            // Interim 1xx responses precede the final one; 101 Switching Protocols is itself final.
            if (status >= 100 && status < 200 && status != 101) {
                continue;
            }
            String reason = secondSpace < 0 ? "" : statusLine.substring(secondSpace + 1);
            byte[] body = readBody(in, method, status, headers);
            return new Response(statusLine, statusLine.substring(0, firstSpace), status, reason, headers, body);
        }
    }

    private static int parseStatus(String code, String statusLine) throws IOException {
        if (code.isEmpty() || !isDigits(code)) {
            throw new IOException("malformed status line: " + statusLine);
        }
        try {
            return Integer.parseInt(code);
        } catch (NumberFormatException e) {
            throw new IOException("malformed status line: " + statusLine, e);
        }
    }

    private static List<Map.Entry<String, String>> readHeaders(InputStream in) throws IOException {
        List<String> names = new ArrayList<>();
        List<String> values = new ArrayList<>();
        while (true) {
            String line = readLine(in);
            if (line == null) {
                throw new IOException("connection closed while reading response headers");
            }
            if (line.isEmpty()) {
                break;
            }
            char first = line.charAt(0);
            if (first == ' ' || first == '\t') {
                // Obsolete line folding continues the previous header value.
                if (names.isEmpty()) {
                    throw new IOException("malformed header line: " + line);
                }
                String continuation = trimSpacesAndTabs(line);
                int last = values.size() - 1;
                String previous = values.get(last);
                values.set(last, previous.isEmpty() ? continuation : previous + " " + continuation);
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                throw new IOException("malformed header line: " + line);
            }
            names.add(line.substring(0, colon));
            values.add(trimSpacesAndTabs(line.substring(colon + 1)));
        }
        List<Map.Entry<String, String>> headers = new ArrayList<>(names.size());
        for (int i = 0; i < names.size(); i++) {
            headers.add(Map.entry(names.get(i), values.get(i)));
        }
        return headers;
    }

    private static byte[] readBody(InputStream in, String method, int status, List<Map.Entry<String, String>> headers)
            throws IOException {
        if ("HEAD".equals(method) || (status >= 100 && status < 200) || status == 204 || status == 304) {
            return new byte[0];
        }
        List<String> transferEncodings = values(headers, "Transfer-Encoding");
        if (!transferEncodings.isEmpty()) {
            if (lastTransferCoding(transferEncodings).equalsIgnoreCase("chunked")) {
                return readChunked(in);
            }
            // RFC 9112 6.3: a final coding other than chunked means the body ends when the server closes,
            // and Transfer-Encoding overrides any Content-Length.
            return in.readAllBytes();
        }
        List<String> contentLengths = values(headers, "Content-Length");
        if (!contentLengths.isEmpty()) {
            return readExactly(in, contentLength(contentLengths), "premature EOF");
        }
        // No framing: the request sent Connection: close, so the body runs to end of stream.
        return in.readAllBytes();
    }

    private static String lastTransferCoding(List<String> transferEncodings) {
        String lastHeader = transferEncodings.get(transferEncodings.size() - 1);
        String coding = lastHeader.substring(lastHeader.lastIndexOf(',') + 1);
        int parameters = coding.indexOf(';');
        return trimSpacesAndTabs(parameters < 0 ? coding : coding.substring(0, parameters));
    }

    private static int contentLength(List<String> contentLengths) throws IOException {
        long length = -1;
        for (String header : contentLengths) {
            for (String element : header.split(",", -1)) {
                String token = trimSpacesAndTabs(element);
                if (token.isEmpty() || !isDigits(token)) {
                    throw new IOException("malformed Content-Length: " + header);
                }
                long value = boundedValue(token, 10);
                if (value > MAX_BODY_LENGTH) {
                    throw new IOException("Content-Length too large for an in-memory body: " + token);
                }
                if (length >= 0 && value != length) {
                    throw new IOException("conflicting Content-Length values: " + contentLengths);
                }
                length = value;
            }
        }
        return (int) length;
    }

    private static byte[] readChunked(InputStream in) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readChunkedLine(in, "a chunk size line");
            int extension = sizeLine.indexOf(';');
            String size = trimSpacesAndTabs(extension < 0 ? sizeLine : sizeLine.substring(0, extension));
            int chunkSize = parseChunkSize(size, sizeLine);
            if (chunkSize == 0) {
                break;
            }
            if (chunkSize > MAX_BODY_LENGTH - body.size()) {
                throw new IOException("chunked body too large for an in-memory body: at least "
                        + ((long) body.size() + chunkSize) + " bytes");
            }
            body.writeBytes(readExactly(in, chunkSize, "premature EOF in chunked body"));
            String terminator = readChunkedLine(in, "CRLF after chunk data");
            if (!terminator.isEmpty()) {
                throw new IOException("malformed chunked body: chunk data not followed by CRLF");
            }
        }
        // Trailer fields are read off the stream and ignored; the body is complete only once the empty line
        // ending the trailer section has arrived.
        String trailer;
        do {
            trailer = readChunkedLine(in, "a trailer field or the empty line ending the chunked body");
        } while (!trailer.isEmpty());
        return body.toByteArray();
    }

    /**
     * Reads one line of chunked framing (a chunk size line, the line ending after chunk data, or a trailer line)
     * like {@link #readLine}, ending at CRLF or a bare LF, terminator excluded, but requires the terminator:
     * end of stream before the line's LF means the response was cut off, whether or not bytes were read.
     *
     * @param expected what the framing requires next, for the premature-EOF message
     */
    private static String readChunkedLine(InputStream in, String expected) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(128);
        int b;
        while ((b = in.read()) != '\n') {
            if (b == -1) {
                String partial = line.size() == 0
                        ? "" : ", got unterminated \"" + line.toString(StandardCharsets.ISO_8859_1) + "\"";
                throw new IOException("premature EOF in chunked body: expected " + expected + partial);
            }
            line.write(b);
        }
        byte[] bytes = line.toByteArray();
        int length = bytes.length > 0 && bytes[bytes.length - 1] == '\r' ? bytes.length - 1 : bytes.length;
        return new String(bytes, 0, length, StandardCharsets.ISO_8859_1);
    }

    private static int parseChunkSize(String size, String sizeLine) throws IOException {
        if (size.isEmpty()) {
            throw new IOException("malformed chunk size: " + sizeLine);
        }
        for (int i = 0; i < size.length(); i++) {
            if (Character.digit(size.charAt(i), 16) < 0) {
                throw new IOException("malformed chunk size: " + sizeLine);
            }
        }
        long chunkSize = boundedValue(size, 16);
        if (chunkSize > MAX_BODY_LENGTH) {
            throw new IOException("chunk size too large for an in-memory body: " + sizeLine);
        }
        return (int) chunkSize;
    }

    /**
     * The value of a non-empty run of digits valid in {@code radix}, any number of leading zeros included, or
     * {@code MAX_BODY_LENGTH + 1} as soon as the accumulated value exceeds {@link #MAX_BODY_LENGTH}, so no digit
     * count can overflow it.
     */
    private static long boundedValue(String digits, int radix) {
        long value = 0;
        for (int i = 0; i < digits.length(); i++) {
            value = value * radix + Character.digit(digits.charAt(i), radix);
            if (value > MAX_BODY_LENGTH) {
                return MAX_BODY_LENGTH + 1L;
            }
        }
        return value;
    }

    /**
     * Reads exactly {@code length} bytes; buffers grow with the bytes actually received, so a large declared
     * length that the stream does not deliver ends in an {@link IOException}, not an allocation of that length.
     */
    private static byte[] readExactly(InputStream in, int length, String eofMessage) throws IOException {
        byte[] bytes = in.readNBytes(length);
        if (bytes.length < length) {
            throw new IOException(eofMessage + ": expected " + length + " bytes, got " + bytes.length);
        }
        return bytes;
    }

    /**
     * Reads one line decoded as ISO-8859-1 (byte-preserving), ending at CRLF or a bare LF, terminator excluded.
     * Returns {@code null} at end of stream before any byte; a final unterminated line is returned as read.
     */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(128);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                byte[] bytes = line.toByteArray();
                int length = bytes.length > 0 && bytes[bytes.length - 1] == '\r' ? bytes.length - 1 : bytes.length;
                return new String(bytes, 0, length, StandardCharsets.ISO_8859_1);
            }
            line.write(b);
        }
        return line.size() == 0 ? null : line.toString(StandardCharsets.ISO_8859_1);
    }

    private static boolean isDigits(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static String trimSpacesAndTabs(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && (text.charAt(start) == ' ' || text.charAt(start) == '\t')) {
            start++;
        }
        while (end > start && (text.charAt(end - 1) == ' ' || text.charAt(end - 1) == '\t')) {
            end--;
        }
        return text.substring(start, end);
    }

    private static List<String> values(List<Map.Entry<String, String>> headers, String name) {
        List<String> values = new ArrayList<>();
        for (Map.Entry<String, String> header : headers) {
            if (header.getKey().equalsIgnoreCase(name)) {
                values.add(header.getValue());
            }
        }
        return values;
    }

    /** One final HTTP response exactly as received: raw status line, headers in arrival order, body bytes. */
    public static final class Response {

        private static final int TO_STRING_BODY_LIMIT = 2_000;

        private final String statusLine;
        private final String httpVersion;
        private final int status;
        private final String reason;
        private final List<Map.Entry<String, String>> headers;
        private final byte[] body;

        private Response(String statusLine, String httpVersion, int status, String reason,
                List<Map.Entry<String, String>> headers, byte[] body) {
            this.statusLine = statusLine;
            this.httpVersion = httpVersion;
            this.status = status;
            this.reason = reason;
            this.headers = List.copyOf(headers);
            this.body = body.clone();
        }

        /** The status line as received, without its line terminator, e.g. {@code HTTP/1.1 400 Invalid input data}. */
        public String statusLine() {
            return statusLine;
        }

        /** The text before the first space of the status line, e.g. {@code HTTP/1.1}. */
        public String httpVersion() {
            return httpVersion;
        }

        /** The numeric status code. */
        public int status() {
            return status;
        }

        /**
         * Everything after the second space of the status line, verbatim (it may contain spaces); empty when the
         * server sent no reason phrase.
         */
        public String reason() {
            return reason;
        }

        /**
         * Every header in arrival order, duplicates retained, names with the casing received, values trimmed of
         * surrounding spaces and tabs; unmodifiable.
         */
        public List<Map.Entry<String, String>> headers() {
            return headers;
        }

        /** The first value of the header named {@code name}, compared ignoring case, or {@code null} when absent. */
        public String header(String name) {
            Objects.requireNonNull(name, "name");
            for (Map.Entry<String, String> header : headers) {
                if (header.getKey().equalsIgnoreCase(name)) {
                    return header.getValue();
                }
            }
            return null;
        }

        /** Every value of the header named {@code name}, compared ignoring case, in arrival order; unmodifiable. */
        public List<String> headers(String name) {
            Objects.requireNonNull(name, "name");
            return List.copyOf(values(headers, name));
        }

        /** A copy of the body bytes as received (chunked framing removed); empty, never {@code null}. */
        public byte[] body() {
            return body.clone();
        }

        /** The body decoded as UTF-8. */
        public String bodyAsString() {
            return new String(body, StandardCharsets.UTF_8);
        }

        /** Status line, headers and the UTF-8 body (truncated), for readable assertion failures. */
        @Override
        public String toString() {
            StringBuilder text = new StringBuilder(statusLine).append('\n');
            for (Map.Entry<String, String> header : headers) {
                text.append(header.getKey()).append(": ").append(header.getValue()).append('\n');
            }
            text.append('\n');
            String bodyText = bodyAsString();
            if (bodyText.length() > TO_STRING_BODY_LIMIT) {
                text.append(bodyText, 0, TO_STRING_BODY_LIMIT)
                        .append("... [truncated, ").append(bodyText.length()).append(" characters in total]");
            } else {
                text.append(bodyText);
            }
            return text.toString();
        }
    }
}
