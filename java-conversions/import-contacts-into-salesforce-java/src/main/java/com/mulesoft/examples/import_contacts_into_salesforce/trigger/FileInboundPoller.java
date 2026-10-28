/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.import_contacts_into_salesforce.trigger;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mule 3.8 {@code file:inbound-endpoint} semantics on the default file connector: shared component SC-5 of the
 * conversion plan.
 *
 * <p>The endpoints this class replaces declare no {@code file:connector}, so the connector defaults hold:
 * {@code streaming} and {@code autoDelete} are true and missing directories are created. Under those defaults
 * Mule's streaming receive path consumes a file whenever the flow returns, whether it completed or ended with an
 * exception the default exception strategy caught and logged at ERROR; a file is kept only on a transaction
 * rollback, and no endpoint this class serves declares a transaction. One {@link #pollOnce()} therefore:
 * <ol>
 *   <li>creates the input directory and, when set, the move directory;</li>
 *   <li>lists the regular files of the input directory in directory-listing order, unsorted, because no
 *       comparator is configured, and without filename, age or MIME filtering;</li>
 *   <li>marks each file in process, so an overlapping or re-entrant poll on the same instance skips it;</li>
 *   <li>reads the file and hands it to the {@link FileHandler}; a file that cannot be read, including one too large
 *       to hold in memory, is left in place and unmarked, so the next poll tries it again, and the poll goes on
 *       with the next file;</li>
 *   <li>after the handler returns or throws, moves the file to the move directory under its own name
 *       (copy-and-delete when a rename fails) or, without a move directory, deletes it.</li>
 * </ol>
 *
 * <p>The class neither schedules itself nor starts threads: the owning trigger calls {@link #pollOnce()} at the
 * endpoint's polling frequency. It is thread-safe; concurrent polls never hand the same file to the handler
 * twice.
 *
 * <p>Every diagnostic it logs stays on one line and discloses no file content or exception text. A file or
 * directory is named by its path or file name with the reverse solidus, CR, LF and TAB written as {@code \\},
 * {@code \r}, {@code \n} and {@code \t}, every other ISO control character and the line and paragraph separators
 * written as {@code \}{@code uXXXX}, and the rendering cut to a bounded length with a {@code ...[truncated]}
 * marker, so a file name cannot forge a log record. An exception is named only by its class, the frame that threw
 * it and the classes of its causes, never by its message, a cause's message, a suppressed exception or a stack
 * trace, and it is never handed to the logger as a throwable; for a {@link FileSystemException} raised by the
 * poller's own file operations the reason is added, escaped and bounded in the same way: the operating system's
 * error text, or the poller's own statement of a file's size and the read limit, never a path.
 *
 * <p>It depends on the JDK and SLF4J alone, and names no project type, so that every file-triggered project can
 * copy it with only its {@code package} line changed.
 */
public class FileInboundPoller {

    private static final Logger LOG = LoggerFactory.getLogger(FileInboundPoller.class);

    /** Longest logged rendering of one path, name or text, in characters after escaping. */
    private static final int LOG_TEXT_LIMIT = 256;

    /** Number of causes named after the outermost exception before the chain is cut short. */
    private static final int CAUSE_LOG_LIMIT = 4;

    private static final String TRUNCATION_LOG_MARKER = "...[truncated]";

    private static final String CAUSE_SEPARATOR = " <- ";

    private static final String MORE_CAUSES_MARKER = "...";

    private static final String CIRCULAR_CAUSE_MARKER = "[circular]";

    private static final String UNAVAILABLE_LOG_MARKER = "[unavailable]";

    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    /**
     * Largest file, in bytes, that {@link #readContent(Path)} reads: the JDK's soft maximum array length
     * ({@code jdk.internal.util.ArraysSupport.SOFT_MAX_ARRAY_LENGTH}), the largest {@code byte[]} it allocates when
     * it grows a buffer. A longer array may exceed the virtual machine's own limit, and
     * {@link Files#readAllBytes(Path)} throws {@link OutOfMemoryError} for every file longer than
     * {@link Integer#MAX_VALUE}, so a longer file is reported as unreadable before any allocation is attempted.
     */
    static final long MAX_CONTENT_LENGTH = Integer.MAX_VALUE - 8;

    /**
     * The flow a polled file is handed to. Any exception it throws is logged at ERROR by the poller, naming only
     * the file and the exception's type and throw site, never its message, and the file is still consumed, as
     * Mule's default exception strategy ends the flow normally.
     */
    @FunctionalInterface
    public interface FileHandler {

        /**
         * Processes one polled file.
         *
         * @param file the file and the bytes read from it
         * @throws Exception any failure of the processing; it never escapes {@link FileInboundPoller#pollOnce()}
         */
        void handle(InboundFile file) throws Exception;
    }

    /**
     * One polled file.
     *
     * @param path    the file's path in the input directory as it was listed, that is the input directory as
     *                configured (relative or absolute) resolved with the file name
     * @param name    the file name, {@code path.getFileName().toString()}
     * @param content the bytes read from the file
     */
    public record InboundFile(Path path, String name, byte[] content) {

        @Override
        public String toString() {
            return "InboundFile[path=" + path + ", name=" + name + ", content=" + content.length + " bytes]";
        }
    }

    private final Path inputDirectory;

    private final Path moveToDirectory;

    private final FileHandler handler;

    /** Absolute, normalized paths of the files currently handed out by any poll of this instance. */
    private final Set<Path> inProcess = ConcurrentHashMap.newKeySet();

    /**
     * Creates a poller. Relative directories resolve against the working directory each time they are used, as
     * Mule resolved an endpoint {@code path} against the application directory.
     *
     * @param inputDirectory  the endpoint's {@code path}
     * @param moveToDirectory the endpoint's {@code moveToDirectory}, or {@code null} to delete each file once
     *                        processed
     * @param handler         the flow each file is handed to
     */
    public FileInboundPoller(Path inputDirectory, Path moveToDirectory, FileHandler handler) {
        this.inputDirectory = Objects.requireNonNull(inputDirectory, "inputDirectory");
        this.moveToDirectory = moveToDirectory;
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    /**
     * Returns the directory this poller lists.
     *
     * @return the input directory as configured
     */
    public Path inputDirectory() {
        return inputDirectory;
    }

    /**
     * Returns the directory processed files are moved to.
     *
     * @return the move directory, or empty when processed files are deleted
     */
    public Optional<Path> moveToDirectory() {
        return Optional.ofNullable(moveToDirectory);
    }

    /**
     * Runs one poll of the input directory. I/O and handler failures are logged and never thrown.
     *
     * @return the number of files handed to the handler in this poll, counting a file whose handler threw; files
     *         skipped because they were in process or could not be read are not counted
     */
    public int pollOnce() {
        if (!createDirectory(inputDirectory) || (moveToDirectory != null && !createDirectory(moveToDirectory))) {
            return 0;
        }

        // File.listFiles keeps the platform's directory-listing order: the receiver sorts only with a configured
        // comparator, and none is.
        File[] listed = inputDirectory.toFile().listFiles();
        if (listed == null) {
            LOG.warn("Could not list input directory {}", safeText(inputDirectory));
            return 0;
        }

        int handed = 0;
        for (File file : listed) {
            if (file.isFile() && processFile(file.toPath())) {
                handed++;
            }
        }
        return handed;
    }

    /**
     * Reads a file for the handler. Overridable so tests can simulate a file that cannot be opened.
     *
     * @param file the listed file
     * @return the file's bytes
     * @throws IOException when the file cannot be read, including a {@link FileSystemException} for {@code file}
     *                     whose reason gives the file's size and {@link #MAX_CONTENT_LENGTH}, never its path, when
     *                     the file is longer than that limit
     */
    protected byte[] readContent(Path file) throws IOException {
        long size = Files.size(file);
        if (size > MAX_CONTENT_LENGTH) {
            throw new FileSystemException(file.toString(), null,
                    "file size " + size + " bytes exceeds the read limit of " + MAX_CONTENT_LENGTH + " bytes");
        }
        return Files.readAllBytes(file);
    }

    /**
     * Marks, reads, hands over and disposes of one file.
     *
     * @return whether the file was handed to the handler
     */
    private boolean processFile(Path path) {
        Path key = path.toAbsolutePath().normalize();
        if (!inProcess.add(key)) {
            return false;
        }
        try {
            byte[] content;
            try {
                content = readContent(path);
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                // OutOfMemoryError is caught here only: a file whose bytes cannot be allocated, because it is larger
                // than the heap or grew past the size check while being read, is a file that cannot be read, and the
                // failed allocation holds nothing once unwound.
                LOG.warn("Could not open file {}, leaving it for the next poll: {}", safeText(path), describe(e, true));
                return false;
            }

            String name = path.getFileName().toString();
            try {
                handler.handle(new InboundFile(path, name, content));
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    // The file is still consumed below; the interrupt is kept for the scheduler that owns the thread.
                    Thread.currentThread().interrupt();
                }
                // Only the exception's type identifiers are logged: the handler parses and forwards file records,
                // so its exception texts can repeat record fields or credentials.
                LOG.error("Exception processing file {}: {}", safeText(name), describe(e, false));
            }

            dispose(path, name);
            return true;
        } finally {
            inProcess.remove(key);
        }
    }

    /** Consumes a processed file: identical after a completed and after a failed handler. */
    private void dispose(Path path, String name) {
        if (moveToDirectory == null) {
            try {
                if (!Files.deleteIfExists(path)) {
                    LOG.warn("Failed to delete file {}: it no longer exists", safeText(path));
                }
            } catch (IOException | RuntimeException e) {
                LOG.warn("Failed to delete file {}: {}", safeText(path), describe(e, true));
            }
            return;
        }

        Path target = moveToDirectory.resolve(name);
        try {
            Files.move(path, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException renameFailure) {
            // A rename fails across file stores; the file is then copied and the original deleted.
            try {
                Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                Files.delete(path);
            } catch (IOException | RuntimeException copyFailure) {
                LOG.warn("Failed to move file {} to {}: rename failed with {}, copy and delete failed with {}",
                        safeText(path), safeText(target), describe(renameFailure, true), describe(copyFailure, true));
            }
        }
    }

    private static boolean createDirectory(Path directory) {
        try {
            Files.createDirectories(directory);
            return true;
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not create directory {}: {}", safeText(directory), describe(e, true));
            return false;
        }
    }

    /**
     * Describes {@code failure} by its type identifiers alone: its class name and throw-site frame, then the class
     * name of each cause, separated by {@code " <- "}, for example {@code java.io.UncheckedIOException at
     * a.b.C.d(C.java:18) <- java.nio.file.NoSuchFileException}. No message, localized message or
     * {@code toString()} of any exception and nothing of its suppressed exceptions is read. The walk stops at a
     * cause already named, marked {@code [circular]}, and after {@link #CAUSE_LOG_LIMIT} causes, marked
     * {@code ...}; a stack trace or cause that cannot be read is marked {@code [unavailable]}. Never throws.
     *
     * @param failure    the exception to describe
     * @param withReason whether to add {@code [reason: <reason>]} when {@code failure} is a
     *                   {@link FileSystemException} carrying a reason, which the JDK fills with the operating
     *                   system's error text and {@link #readContent(Path)} with the file's size and the read limit,
     *                   never with the path; only the poller's own file operations pass
     *                   {@code true}, because a handler may construct such an exception with any text
     * @return the description, escaped and bounded part by part as {@link #safeText(Object)} does
     */
    private static String describe(Throwable failure, boolean withReason) {
        if (failure == null) {
            return safeText(null);
        }
        StringBuilder out = new StringBuilder(160);
        appendTypeAndThrowSite(out, failure);
        if (withReason && failure instanceof FileSystemException fileSystemFailure) {
            appendReason(out, fileSystemFailure);
        }
        Set<Throwable> named = Collections.newSetFromMap(new IdentityHashMap<>());
        named.add(failure);
        int causes = 0;
        Throwable current = failure;
        while (true) {
            Throwable cause;
            try {
                cause = current.getCause();
            } catch (RuntimeException unreadable) {
                out.append(CAUSE_SEPARATOR).append(UNAVAILABLE_LOG_MARKER);
                break;
            }
            if (cause == null) {
                break;
            }
            out.append(CAUSE_SEPARATOR);
            if (causes == CAUSE_LOG_LIMIT) {
                out.append(MORE_CAUSES_MARKER);
                break;
            }
            if (!named.add(cause)) {
                out.append(CIRCULAR_CAUSE_MARKER);
                break;
            }
            out.append(safeText(cause.getClass().getName()));
            causes++;
            current = cause;
        }
        return out.toString();
    }

    /**
     * Appends the class name of {@code failure} and {@code " at <class>.<method>(<file>:<line>)"} for the frame
     * that threw it, in the form {@link StackTraceElement#toString()} uses without its class loader and module
     * prefix; the frame is left out when the stack trace is empty.
     */
    private static void appendTypeAndThrowSite(StringBuilder out, Throwable failure) {
        out.append(safeText(failure.getClass().getName()));
        StackTraceElement frame;
        try {
            StackTraceElement[] trace = failure.getStackTrace();
            frame = trace == null || trace.length == 0 ? null : trace[0];
        } catch (RuntimeException unreadable) {
            out.append(" at ").append(UNAVAILABLE_LOG_MARKER);
            return;
        }
        if (frame == null) {
            return;
        }
        out.append(" at ")
                .append(safeText(frame.getClassName()))
                .append('.')
                .append(safeText(frame.getMethodName()))
                .append('(');
        if (frame.isNativeMethod()) {
            out.append("Native Method");
        } else if (frame.getFileName() == null) {
            out.append("Unknown Source");
        } else {
            out.append(safeText(frame.getFileName()));
            if (frame.getLineNumber() >= 0) {
                out.append(':').append(frame.getLineNumber());
            }
        }
        out.append(')');
    }

    /** Appends {@code " [reason: <reason>]"} when the reason is present and readable. */
    private static void appendReason(StringBuilder out, FileSystemException failure) {
        String reason;
        try {
            reason = failure.getReason();
        } catch (RuntimeException unreadable) {
            return;
        }
        if (reason != null) {
            out.append(" [reason: ").append(safeText(reason)).append(']');
        }
    }

    /**
     * Renders {@code value} for a log line, so a path, name or text cannot break the line or forge a record.
     *
     * <p>The reverse solidus, CR, LF and TAB are written as {@code \\}, {@code \r}, {@code \n} and {@code \t};
     * every other ISO control character (C0, DEL and C1, NEL included) and the line and paragraph separators
     * U+2028 and U+2029 are written as {@code \}{@code u} and four lower-case hex digits. Escaping the reverse
     * solidus keeps an escape distinguishable from the same characters in the value. Every other character, a
     * surrogate pair included, is copied unchanged. Never throws.
     *
     * @param value the value, rendered with {@link String#valueOf(Object)}, so {@code null} is written as
     *              {@code null}; a value whose {@code toString()} throws is written as {@code [unavailable]}
     * @return the rendering, at most {@link #LOG_TEXT_LIMIT} characters before the {@code ...[truncated]} marker
     *         appended to a longer value, which is cut before the first character or escape that would exceed the
     *         limit, never inside an escape or a surrogate pair
     */
    private static String safeText(Object value) {
        String text;
        try {
            text = String.valueOf(value);
        } catch (RuntimeException unreadable) {
            return UNAVAILABLE_LOG_MARKER;
        }
        if (text == null) {
            text = "null";
        }
        StringBuilder out = new StringBuilder(Math.min(text.length(), LOG_TEXT_LIMIT) + TRUNCATION_LOG_MARKER.length());
        int i = 0;
        while (i < text.length()) {
            int codePoint = text.codePointAt(i);
            int units = Character.charCount(codePoint);
            String escape = escapeOf(codePoint);
            if (out.length() + (escape == null ? units : escape.length()) > LOG_TEXT_LIMIT) {
                return out.append(TRUNCATION_LOG_MARKER).toString();
            }
            if (escape == null) {
                out.append(text, i, i + units);
            } else {
                out.append(escape);
            }
            i += units;
        }
        return out.toString();
    }

    /** Returns the escape {@link #safeText(Object)} writes for {@code codePoint}, or {@code null} to copy it. */
    private static String escapeOf(int codePoint) {
        return switch (codePoint) {
            case '\\' -> "\\\\";
            case '\r' -> "\\r";
            case '\n' -> "\\n";
            case '\t' -> "\\t";
            case '\u2028', '\u2029' -> unicodeEscape(codePoint);
            default -> Character.isISOControl(codePoint) ? unicodeEscape(codePoint) : null;
        };
    }

    private static String unicodeEscape(int codePoint) {
        return new String(new char[] {
            '\\', 'u',
            HEX_DIGITS[(codePoint >> 12) & 0xF],
            HEX_DIGITS[(codePoint >> 8) & 0xF],
            HEX_DIGITS[(codePoint >> 4) & 0xF],
            HEX_DIGITS[codePoint & 0xF]
        });
    }
}
