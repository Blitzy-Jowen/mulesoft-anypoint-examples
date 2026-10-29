package com.mulesoft.examples.import_leads_into_salesforce.trigger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Tests the log safety of shared component SC-5, {@link FileInboundPoller}: every diagnostic stays on one line
 * whatever the file name, names exceptions only by their type identifiers, never by their message, cause messages
 * or suppressed exceptions, and attaches no throwable, while file disposition is unchanged.
 *
 * <p>It supplements {@code FileInboundPollerTest}, the SC-5 reference test, which every SC-5 consumer carries. This
 * class is carried only by {@code processing-orders-with-dataweave-and-APIkit}, its reference, and by
 * {@code import-leads-into-salesforce}, a copy with only its {@code package} line changed; the other SC-5 consumers
 * carry {@code FileInboundPollerTest} alone. It depends on the JDK, JUnit, SLF4J, Logback and
 * {@link FileInboundPoller} alone, so that it copies with only that line changed. File and directory names carry
 * CR, LF, TAB, the reverse solidus and other control characters, which POSIX file systems accept, and every
 * directory and every link target lives under {@link TempDir}.
 */
class FileInboundPollerLogSafetyTest {

    private static final byte[] CONTENT = "Name,Email\nJane,jane.doe@example.com\n".getBytes(StandardCharsets.UTF_8);

    private static final String EMAIL = "jane.doe@example.com";

    private static final String PASSWORD = "password=hunter2";

    private static final String TOKEN = "token=s3cr3t-token";

    /** Every value a logged event must not contain: the sensitive values and the forged-line markers. */
    private static final List<String> UNDISCLOSED = List.of(EMAIL, "hunter2", "s3cr3t-token", "forged-by-");

    /** The characters a log viewer may break a line at; no formatted message may contain one. */
    private static final char[] LINE_BREAKS = {'\r', '\n', '\u0085', '\u2028', '\u2029'};

    private static final String FORGING_NAME = "contacts\nINFO forged.csv";

    private static final String FORGING_NAME_RENDERING = "contacts\\nINFO forged.csv";

    /** A 205-character name whose escaped form exceeds the 256-character rendering limit. */
    private static final String LONG_NAME = "a" + "\n".repeat(200) + ".csv";

    private static final String LONG_NAME_RENDERING = "a" + "\\n".repeat(127) + "...[truncated]";

    /** A JDK throw-site frame as the poller renders it: {@code class.method(File.java:line)}. */
    private static final String JDK_FRAME = "[\\w.$<>]+\\([\\w.$]+:\\d+\\)";

    @TempDir
    Path tempDir;

    private Path inputDirectory;

    private Path moveDirectory;

    private Logger logger;

    private Level previousLevel;

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        inputDirectory = tempDir.resolve("input");
        moveDirectory = tempDir.resolve("moved");
        logger = (Logger) LoggerFactory.getLogger(FileInboundPoller.class);
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

    static Stream<Arguments> forgingFileNames() {
        String controlName = "leads\r\nWARN forged\t\\ \u001b[2J\u007f.csv";
        String controlRendering = "leads\\r\\nWARN forged\\t\\\\ \\u001b[2J\\u007f.csv";
        return Stream.of(
                Arguments.of("LF in the name, file deleted", FORGING_NAME, FORGING_NAME_RENDERING, false),
                Arguments.of("LF in the name, file moved", FORGING_NAME, FORGING_NAME_RENDERING, true),
                Arguments.of("CR, LF, TAB, ESC, DEL and reverse solidus, file deleted",
                        controlName, controlRendering, false),
                Arguments.of("CR, LF, TAB, ESC, DEL and reverse solidus, file moved",
                        controlName, controlRendering, true),
                Arguments.of("name longer than the rendering limit, file deleted", LONG_NAME, LONG_NAME_RENDERING,
                        false),
                Arguments.of("name longer than the rendering limit, file moved", LONG_NAME, LONG_NAME_RENDERING,
                        true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("forgingFileNames")
    void handlerFailureLogsOneSafeErrorAndStillConsumesTheFile(String label, String fileName, String rendering,
                                                                 boolean withMoveDirectory) throws IOException {
        Path file = writeInputFile(fileName);
        AtomicReference<FileInboundPoller.InboundFile> received = new AtomicReference<>();
        FileInboundPoller poller = new FileInboundPoller(inputDirectory, withMoveDirectory ? moveDirectory : null,
                inbound -> {
                    received.set(inbound);
                    throw sensitiveFailure();
                });

        assertEquals(1, poller.pollOnce());

        assertEquals(fileName, received.get().name());
        assertEquals(file, received.get().path());
        List<ILoggingEvent> events = loggedEvents();
        assertEquals(1, events.size());
        assertEquals(Level.ERROR, events.get(0).getLevel());
        String message = events.get(0).getFormattedMessage();
        String prefix = "Exception processing file " + rendering + ": java.lang.IllegalStateException at "
                + FileInboundPollerLogSafetyTest.class.getName()
                + ".sensitiveFailure(FileInboundPollerLogSafetyTest.java:";
        assertTrue(message.startsWith(prefix), message);
        assertTrue(message.endsWith(") <- java.io.IOException"), message);
        assertFalse(message.contains("IllegalArgumentException"), message);
        assertDisclosesNothing(message);

        assertFalse(Files.exists(file));
        if (withMoveDirectory) {
            assertArrayEquals(CONTENT, Files.readAllBytes(moveDirectory.resolve(fileName)));
        } else {
            assertFalse(Files.exists(moveDirectory));
        }
    }

    static Stream<Arguments> handlerFailureDescriptions() {
        IllegalStateException withSuppressed = withStackTrace(new IllegalStateException(PASSWORD));
        withSuppressed.addSuppressed(new IllegalArgumentException(EMAIL + "\nINFO forged-by-suppressed"));

        IllegalStateException circularOuter = withStackTrace(new IllegalStateException(EMAIL));
        IOException circularInner = withStackTrace(new IOException(TOKEN));
        circularOuter.initCause(circularInner);
        circularInner.initCause(circularOuter);

        return Stream.of(
                Arguments.of("empty stack trace and a suppressed exception", withSuppressed,
                        "java.lang.IllegalStateException"),
                Arguments.of("file-system reason not logged for a handler failure",
                        withStackTrace(new FileSystemException("/in/contacts.csv", null, PASSWORD)),
                        "java.nio.file.FileSystemException"),
                Arguments.of("source frame with line",
                        withStackTrace(new IllegalStateException(PASSWORD),
                                new StackTraceElement("a.b.Parser", "next", "Parser.java", 42)),
                        "java.lang.IllegalStateException at a.b.Parser.next(Parser.java:42)"),
                Arguments.of("source frame without line",
                        withStackTrace(new IllegalStateException(PASSWORD),
                                new StackTraceElement("a.b.Parser", "next", "Parser.java", -1)),
                        "java.lang.IllegalStateException at a.b.Parser.next(Parser.java)"),
                Arguments.of("frame without source file",
                        withStackTrace(new IllegalStateException(PASSWORD),
                                new StackTraceElement("a.b.Parser", "next", null, 42)),
                        "java.lang.IllegalStateException at a.b.Parser.next(Unknown Source)"),
                Arguments.of("native frame",
                        withStackTrace(new IllegalStateException(PASSWORD),
                                new StackTraceElement("a.b.Parser", "read", "Parser.java", -2)),
                        "java.lang.IllegalStateException at a.b.Parser.read(Native Method)"),
                Arguments.of("frame names with line breaks",
                        withStackTrace(new IllegalStateException(PASSWORD),
                                new StackTraceElement("a.b.Evil\nINFO forged", "next\r", "Evil\u2028.java", 7)),
                        "java.lang.IllegalStateException at a.b.Evil\\nINFO forged.next\\r(Evil\\u2028.java:7)"),
                Arguments.of("circular cause chain", circularOuter,
                        "java.lang.IllegalStateException <- java.io.IOException <- [circular]"),
                Arguments.of("four causes, all named", causeChain(5),
                        "java.lang.RuntimeException <- java.lang.IllegalStateException"
                                + " <- java.lang.IllegalArgumentException <- java.lang.UnsupportedOperationException"
                                + " <- java.io.IOException"),
                Arguments.of("five causes, cut after four", causeChain(6),
                        "java.lang.RuntimeException <- java.lang.IllegalStateException"
                                + " <- java.lang.IllegalArgumentException <- java.lang.UnsupportedOperationException"
                                + " <- java.io.IOException <- ..."),
                Arguments.of("stack trace and cause that throw when read", new UnreadableException(),
                        UnreadableException.class.getName() + " at [unavailable] <- [unavailable]"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("handlerFailureDescriptions")
    void handlerFailureIsDescribedByTypeIdentifiersOnly(String label, Exception failure, String description)
            throws IOException {
        Path file = writeInputFile("contacts.csv");
        FileInboundPoller poller = new FileInboundPoller(inputDirectory, null, inbound -> {
            throw failure;
        });

        assertEquals(1, poller.pollOnce());

        List<ILoggingEvent> events = loggedEvents();
        assertEquals(1, events.size());
        assertEquals(Level.ERROR, events.get(0).getLevel());
        assertEquals("Exception processing file contacts.csv: " + description, events.get(0).getFormattedMessage());
        assertDisclosesNothing(events.get(0).getFormattedMessage());
        assertFalse(Files.exists(file));
    }

    static Stream<Arguments> readFailures() {
        String emoji = "\uD83D\uDE00";
        return Stream.of(
                Arguments.of("IOException repeating the path",
                        (Function<Path, Throwable>) path -> withStackTrace(
                                new IOException("Cannot read " + path + ": " + PASSWORD + "\nERROR forged-by-message")),
                        "java.io.IOException"),
                Arguments.of("UncheckedIOException with a cause",
                        (Function<Path, Throwable>) path -> withStackTrace(new UncheckedIOException(
                                "Wrapped " + EMAIL, withStackTrace(new IOException(path + " " + TOKEN)))),
                        "java.io.UncheckedIOException <- java.io.IOException"),
                Arguments.of("RuntimeException repeating the path",
                        (Function<Path, Throwable>) path -> withStackTrace(
                                new SecurityException(path + "\r\nERROR forged-by-message " + PASSWORD)),
                        "java.lang.SecurityException"),
                Arguments.of("OutOfMemoryError for a file larger than the heap",
                        (Function<Path, Throwable>) path -> withStackTrace(
                                new OutOfMemoryError("Java heap space " + path + " " + PASSWORD)),
                        "java.lang.OutOfMemoryError"),
                Arguments.of("FileSystemException without reason",
                        (Function<Path, Throwable>) path -> withStackTrace(new NoSuchFileException(path.toString())),
                        "java.nio.file.NoSuchFileException"),
                Arguments.of("FileSystemException reason with line breaks and controls",
                        (Function<Path, Throwable>) path -> withStackTrace(new FileSystemException(path.toString(),
                                null, "Input/output error\u2028\u2029\u0085\u0000\t\\")),
                        "java.nio.file.FileSystemException"
                                + " [reason: Input/output error\\u2028\\u2029\\u0085\\u0000\\t\\\\]"),
                Arguments.of("FileSystemException reason cut before a surrogate pair",
                        (Function<Path, Throwable>) path -> withStackTrace(new FileSystemException(path.toString(),
                                null, "x" + emoji.repeat(200))),
                        "java.nio.file.FileSystemException [reason: x" + emoji.repeat(127) + "...[truncated]]"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("readFailures")
    void readFailureLogsOneSafeWarningAndLeavesTheFile(String label, Function<Path, Throwable> failure,
                                                       String description) throws IOException {
        Path file = writeInputFile(FORGING_NAME);
        AtomicInteger handled = new AtomicInteger();
        FileInboundPoller poller = new FileInboundPoller(inputDirectory, null, inbound -> handled.incrementAndGet()) {
            @Override
            protected byte[] readContent(Path listed, SeekableByteChannel channel) throws IOException {
                Throwable thrown = failure.apply(listed);
                if (thrown instanceof IOException ioFailure) {
                    throw ioFailure;
                }
                if (thrown instanceof Error error) {
                    throw error;
                }
                throw (RuntimeException) thrown;
            }
        };

        assertEquals(0, poller.pollOnce());

        assertEquals(0, handled.get());
        List<ILoggingEvent> events = loggedEvents();
        assertEquals(1, events.size());
        assertEquals(Level.WARN, events.get(0).getLevel());
        assertEquals("Could not open file " + inputDirectory + "/" + FORGING_NAME_RENDERING
                        + ", leaving it for the next poll: " + description,
                events.get(0).getFormattedMessage());
        assertDisclosesNothing(events.get(0).getFormattedMessage());
        assertArrayEquals(CONTENT, Files.readAllBytes(file));
    }

    /** What a handler or read hook does to its file before the poller disposes of it. */
    @FunctionalInterface
    interface FileAction {

        void apply(Path file) throws IOException;
    }

    static Stream<Arguments> disposalFailures() {
        String replaced = "Leaving file {file} in place: it was replaced after it was read, so the replacement is"
                + " neither moved nor deleted";
        return Stream.of(
                Arguments.of("file replaced by a non-empty directory, file deleted",
                        (FileAction) file -> {
                            Files.delete(file);
                            Files.createDirectories(file.resolve("child"));
                        },
                        false, replaced),
                Arguments.of("file replaced by a link, file moved",
                        (FileAction) file -> {
                            Files.delete(file);
                            Files.createSymbolicLink(file, Path.of("elsewhere.csv"));
                        },
                        true, replaced),
                Arguments.of("file already removed, file deleted",
                        (FileAction) Files::delete,
                        false, "Failed to delete file {file}: it no longer exists"),
                Arguments.of("file already removed, file moved",
                        (FileAction) Files::delete,
                        true, "Failed to move file {file} to {target}: it no longer exists"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("disposalFailures")
    void disposalFailureLogsOneSafeWarning(String label, FileAction action, boolean withMoveDirectory,
                                           String expected) throws IOException {
        Path file = writeInputFile(FORGING_NAME);
        FileInboundPoller poller = new FileInboundPoller(inputDirectory, withMoveDirectory ? moveDirectory : null,
                inbound -> action.apply(inbound.path()));

        assertEquals(1, poller.pollOnce());

        List<ILoggingEvent> events = loggedEvents();
        assertEquals(1, events.size());
        assertEquals(Level.WARN, events.get(0).getLevel());
        String message = events.get(0).getFormattedMessage();
        assertEquals(expected.replace("{file}", inputDirectory + "/" + FORGING_NAME_RENDERING)
                .replace("{target}", moveDirectory + "/" + FORGING_NAME_RENDERING), message);
        assertDisclosesNothing(message);
        // Nothing reached the move directory, and a replacement is left where it stands.
        assertFalse(Files.exists(moveDirectory.resolve(FORGING_NAME), LinkOption.NOFOLLOW_LINKS));
        assertEquals(expected.startsWith("Leaving"), Files.exists(file, LinkOption.NOFOLLOW_LINKS));
    }

    /** The forging names whose rendering stays within the limit once the input directory precedes it. */
    static Stream<Arguments> forgingLinkNames() {
        return forgingFileNames().filter(arguments -> !LONG_NAME.equals(arguments.get()[1]));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("forgingLinkNames")
    void symbolicLinkLogsOneSafeWarningAndIsLeftInPlace(String label, String linkName, String rendering,
                                                        boolean withMoveDirectory) throws IOException {
        Path outside = Files.write(tempDir.resolve("outside.csv"), CONTENT);
        Files.createDirectories(inputDirectory);
        Path link = Files.createSymbolicLink(inputDirectory.resolve(linkName), outside);
        AtomicInteger handled = new AtomicInteger();
        FileInboundPoller poller = new FileInboundPoller(inputDirectory, withMoveDirectory ? moveDirectory : null,
                inbound -> handled.incrementAndGet());

        assertEquals(0, poller.pollOnce());

        assertEquals(0, handled.get());
        List<ILoggingEvent> events = loggedEvents();
        assertEquals(1, events.size());
        assertEquals(Level.WARN, events.get(0).getLevel());
        assertEquals("Skipping symbolic link " + inputDirectory + "/" + rendering
                + ": links are not followed, leaving it in place", events.get(0).getFormattedMessage());
        assertDisclosesNothing(events.get(0).getFormattedMessage());
        assertTrue(Files.isSymbolicLink(link));
        assertArrayEquals(CONTENT, Files.readAllBytes(outside));
    }

    static Stream<Arguments> changesWhileRead() {
        return Stream.of(
                Arguments.of("file removed", (FileAction) Files::delete, "removed"),
                Arguments.of("file replaced by a link",
                        (FileAction) file -> {
                            Files.delete(file);
                            Files.createSymbolicLink(file, Path.of("elsewhere.csv"));
                        },
                        "replaced"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("changesWhileRead")
    void fileChangedWhileBeingReadLogsOneSafeWarningAndIsNotHandedOver(String label, FileAction action,
                                                                         String change) throws IOException {
        writeInputFile(FORGING_NAME);
        AtomicInteger handled = new AtomicInteger();
        FileInboundPoller poller = new FileInboundPoller(inputDirectory, moveDirectory,
                inbound -> handled.incrementAndGet()) {
            @Override
            protected byte[] readContent(Path listed, SeekableByteChannel channel) throws IOException {
                action.apply(listed);
                return super.readContent(listed, channel);
            }
        };

        assertEquals(0, poller.pollOnce());

        assertEquals(0, handled.get());
        List<ILoggingEvent> events = loggedEvents();
        assertEquals(1, events.size());
        assertEquals(Level.WARN, events.get(0).getLevel());
        String message = events.get(0).getFormattedMessage();
        String expected = Pattern.quote("Could not open file " + inputDirectory + "/" + FORGING_NAME_RENDERING
                        + ", leaving it for the next poll: java.nio.file.FileSystemException at "
                        + FileInboundPoller.class.getName() + ".requireUnchanged(FileInboundPoller.java:")
                + "\\d+"
                + Pattern.quote(") [reason: file was " + change + " while being read]");
        assertTrue(message.matches(expected), message);
        assertDisclosesNothing(message);
        assertFalse(Files.exists(moveDirectory.resolve(FORGING_NAME), LinkOption.NOFOLLOW_LINKS));
    }

    static Stream<Arguments> relocatedDirectories() {
        return Stream.of(
                Arguments.of("input directory redirected through a link", true),
                Arguments.of("move directory redirected through a link", false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("relocatedDirectories")
    void relocatedDirectoryLogsOneSafeWarningAndPollsNothing(String label, boolean relocateInput)
            throws IOException {
        Path input = relocateInput ? tempDir.resolve("input\nINFO forged") : inputDirectory;
        Path move = relocateInput ? moveDirectory : tempDir.resolve("moved\nINFO forged");
        AtomicInteger handled = new AtomicInteger();
        FileInboundPoller poller = new FileInboundPoller(input, move, inbound -> handled.incrementAndGet());
        assertEquals(0, poller.pollOnce());
        Path elsewhere = Files.createDirectories(tempDir.resolve("elsewhere"));
        Path waiting = Files.write((relocateInput ? elsewhere : input).resolve(FORGING_NAME), CONTENT);
        Path relocated = relocateInput ? input : move;
        Files.move(relocated, tempDir.resolve("original"));
        Files.createSymbolicLink(relocated, elsewhere);

        assertEquals(0, poller.pollOnce());

        assertEquals(0, handled.get());
        List<ILoggingEvent> events = loggedEvents();
        assertEquals(1, events.size());
        assertEquals(Level.WARN, events.get(0).getLevel());
        assertEquals("Not polling: the " + (relocateInput ? "input" : "move") + " directory " + tempDir + "/"
                        + (relocateInput ? "input" : "moved") + "\\nINFO forged no longer resolves to the directory it"
                        + " resolved to at the first poll, so nothing is read, moved or deleted; restart to poll a new"
                        + " location",
                events.get(0).getFormattedMessage());
        assertDisclosesNothing(events.get(0).getFormattedMessage());
        assertArrayEquals(CONTENT, Files.readAllBytes(waiting));
        if (!relocateInput) {
            assertFalse(Files.exists(elsewhere.resolve(FORGING_NAME), LinkOption.NOFOLLOW_LINKS));
        }
    }

    static Stream<Arguments> nestedDirectories() {
        return Stream.of(
                Arguments.of("move directory inside the input directory", true),
                Arguments.of("input directory inside the move directory", false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nestedDirectories")
    void nestedDirectoriesByPathLogOneSafeWarningPerPollAndPollNothing(String label, boolean moveInsideInput)
            throws IOException {
        Path outer = tempDir.resolve("outer\nINFO forged");
        Path input = moveInsideInput ? outer : outer.resolve("input\nINFO forged");
        Path move = moveInsideInput ? outer.resolve("moved\nINFO forged") : outer;
        Files.createDirectories(input);
        Path waiting = Files.write(input.resolve(FORGING_NAME), CONTENT);
        AtomicInteger handled = new AtomicInteger();
        FileInboundPoller poller = new FileInboundPoller(input, move, inbound -> handled.incrementAndGet()) {
            @Override
            DirectoryStream<Path> openDirectory(Path directory) throws IOException {
                return new ListingOnlyStream(super.openDirectory(directory));
            }
        };

        assertEquals(0, poller.pollOnce());
        assertEquals(0, poller.pollOnce());

        assertEquals(0, handled.get());
        List<ILoggingEvent> events = loggedEvents();
        assertEquals(2, events.size());
        String outerRendering = tempDir + "/outer\\nINFO forged";
        String expected = "Not polling: the "
                + (moveInsideInput
                        ? "move directory " + outerRendering + "/moved\\nINFO forged lies inside the input directory "
                                + outerRendering
                        : "input directory " + outerRendering + "/input\\nINFO forged lies inside the move directory "
                                + outerRendering)
                + ", and without a secure directory stream a link swapped in for the inner one could redirect the"
                + " poll, so nothing is read, moved or deleted; configure directories that are not nested and restart";
        for (ILoggingEvent event : events) {
            assertEquals(Level.WARN, event.getLevel());
            assertEquals(expected, event.getFormattedMessage());
            assertDisclosesNothing(event.getFormattedMessage());
        }
        assertArrayEquals(CONTENT, Files.readAllBytes(waiting));
        assertFalse(Files.exists(move.resolve(FORGING_NAME), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void moveFailureLogsOneSafeWarningAndKeepsTheFile() throws IOException {
        Path file = writeInputFile(FORGING_NAME);
        Path target = moveDirectory.resolve(FORGING_NAME);
        Files.createDirectories(target.resolve("child"));
        FileInboundPoller poller = new FileInboundPoller(inputDirectory, moveDirectory, inbound -> { });

        assertEquals(1, poller.pollOnce());

        List<ILoggingEvent> events = loggedEvents();
        assertEquals(1, events.size());
        assertEquals(Level.WARN, events.get(0).getLevel());
        String message = events.get(0).getFormattedMessage();
        String expected = Pattern.quote("Failed to move file " + inputDirectory + "/" + FORGING_NAME_RENDERING
                        + " to " + moveDirectory + "/" + FORGING_NAME_RENDERING
                        + ": rename failed with java.nio.file.DirectoryNotEmptyException at ")
                + JDK_FRAME
                + Pattern.quote(", copy and delete failed with java.nio.file.DirectoryNotEmptyException at ")
                + JDK_FRAME;
        assertTrue(message.matches(expected), message);
        assertArrayEquals(CONTENT, Files.readAllBytes(file));
        assertTrue(Files.isDirectory(target.resolve("child")));
    }

    static Stream<Arguments> blockedDirectories() {
        return Stream.of(
                Arguments.of("input directory under a regular file", true),
                Arguments.of("move directory under a regular file", false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("blockedDirectories")
    void createDirectoryFailureLogsOneSafeWarningAndPollsNothing(String label, boolean blockInput)
            throws IOException {
        Path blocker = Files.write(tempDir.resolve("blocker\nINFO forged"), CONTENT);
        Path input = blockInput ? blocker.resolve("input") : inputDirectory;
        Path move = blockInput ? moveDirectory : blocker.resolve("moved");
        Path file = blockInput ? null : writeInputFile(FORGING_NAME);
        AtomicInteger handled = new AtomicInteger();
        FileInboundPoller poller = new FileInboundPoller(input, move, inbound -> handled.incrementAndGet());

        assertEquals(0, poller.pollOnce());

        assertEquals(0, handled.get());
        List<ILoggingEvent> events = loggedEvents();
        assertEquals(1, events.size());
        assertEquals(Level.WARN, events.get(0).getLevel());
        String message = events.get(0).getFormattedMessage();
        String expected = Pattern.quote("Could not create directory " + tempDir + "/blocker\\nINFO forged/"
                        + (blockInput ? "input" : "moved") + ": java.nio.file.FileSystemException at ")
                + JDK_FRAME + " \\[reason: [^\\]]+\\]";
        assertTrue(message.matches(expected), message);
        if (file != null) {
            assertArrayEquals(CONTENT, Files.readAllBytes(file));
        }
    }

    /** A handler failure whose message, cause message and suppressed message carry sensitive record data. */
    private static IllegalStateException sensitiveFailure() {
        IllegalStateException failure = new IllegalStateException(
                "Invalid record " + EMAIL + " " + PASSWORD + "\nINFO forged-by-message",
                new IOException("Vendor rejected " + TOKEN + "\r\nERROR forged-by-cause"));
        failure.addSuppressed(new IllegalArgumentException(
                "Suppressed " + EMAIL + " " + PASSWORD + "\nINFO forged-by-suppressed"));
        return failure;
    }

    /**
     * Builds {@code length} exceptions, each the cause of the one before, with empty stack traces and sensitive
     * messages.
     */
    private static Exception causeChain(int length) {
        List<Exception> chain = List.of(
                new RuntimeException(PASSWORD),
                new IllegalStateException(EMAIL),
                new IllegalArgumentException(TOKEN),
                new UnsupportedOperationException(PASSWORD),
                new IOException(EMAIL),
                new ArithmeticException(TOKEN));
        for (int i = 0; i < length; i++) {
            withStackTrace(chain.get(i));
            if (i + 1 < length) {
                chain.get(i).initCause(chain.get(i + 1));
            }
        }
        return chain.get(0);
    }

    private static <T extends Throwable> T withStackTrace(T failure, StackTraceElement... frames) {
        failure.setStackTrace(frames);
        return failure;
    }

    private Path writeInputFile(String name) throws IOException {
        Files.createDirectories(inputDirectory);
        return Files.write(inputDirectory.resolve(name), CONTENT);
    }

    /**
     * Returns the captured events after checking that none breaks a line or carries a throwable, which Logback
     * would print with its message, causes and suppressed exceptions.
     */
    private List<ILoggingEvent> loggedEvents() {
        List<ILoggingEvent> events = new ArrayList<>(appender.list);
        for (ILoggingEvent event : events) {
            String message = event.getFormattedMessage();
            for (char lineBreak : LINE_BREAKS) {
                assertEquals(-1, message.indexOf(lineBreak),
                        () -> "line break U+" + String.format("%04X", (int) lineBreak) + " in " + message);
            }
            assertNull(event.getThrowableProxy(), message);
        }
        return events;
    }

    private static void assertDisclosesNothing(String message) {
        for (String undisclosed : UNDISCLOSED) {
            assertFalse(message.contains(undisclosed), () -> "'" + undisclosed + "' disclosed in " + message);
        }
    }

    /** A plain directory stream over the platform's, so that the poller takes its path-based operations. */
    private static final class ListingOnlyStream implements DirectoryStream<Path> {

        private final DirectoryStream<Path> delegate;

        ListingOnlyStream(DirectoryStream<Path> delegate) {
            this.delegate = delegate;
        }

        @Override
        public Iterator<Path> iterator() {
            return delegate.iterator();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    /** An exception whose stack trace and cause cannot be read, as a hostile or broken subclass may behave. */
    static final class UnreadableException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        UnreadableException() {
            super(PASSWORD);
        }

        @Override
        public StackTraceElement[] getStackTrace() {
            throw new IllegalStateException(PASSWORD);
        }

        @Override
        public synchronized Throwable getCause() {
            throw new IllegalStateException(PASSWORD);
        }
    }
}
