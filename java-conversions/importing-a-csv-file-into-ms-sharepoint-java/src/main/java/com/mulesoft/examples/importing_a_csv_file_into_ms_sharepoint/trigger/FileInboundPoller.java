/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.importing_a_csv_file_into_ms_sharepoint.trigger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

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
 *   <li>opens each directory at its canonical location, pinned at the first poll that resolves it, and polls nothing
 *       while the configured directory resolves elsewhere or the opened directory is not the one standing at that
 *       location, nor, where the platform provides no secure directory stream, while either directory lies inside
 *       the other;</li>
 *   <li>lists the entries of the input directory in directory-listing order, unsorted, because no comparator is
 *       configured, and without filename, age or MIME filtering, and takes its regular files: a symbolic link is
 *       skipped with a WARN and left in place, and a subdirectory or any other entry is skipped;</li>
 *   <li>marks each file in process, so an overlapping or re-entrant poll on the same instance skips it;</li>
 *   <li>reads the file and hands it to the {@link FileHandler}; a file that cannot be read, including one too large
 *       to hold in memory and one removed or replaced while it was read, is left in place and unmarked, so the next
 *       poll tries it again, and the poll goes on with the next file;</li>
 *   <li>after the handler returns or throws, moves the file to the move directory under its own name
 *       (copy-and-delete when a rename fails) or, without a move directory, deletes it; when the file was removed
 *       or replaced after it was read, whatever stands at its name is neither moved nor deleted.</li>
 * </ol>
 *
 * <p>Both directories may be writable by parties the application does not trust. A symbolic link, wherever it
 * points, is never followed, read, handed over, moved or deleted, and a file operation always refuses a link at the
 * entry it names: a file is read only as itself, a move renames the entry and replaces an entry of the same name in
 * the move directory instead of writing through it, and the copy fallback creates a new file there. A file's
 * identity, its file key or, where the file system reports none, its creation time, is taken when the file's turn
 * comes and checked again after the read and before disposal. A configured directory that a link later redirects
 * elsewhere is refused until it resolves to its pinned location again or a restart pins the new one; a directory
 * recreated at the same location keeps being polled. How far the directories are confined depends on the platform:
 * <ul>
 *   <li>Where the platform provides a {@link SecureDirectoryStream}, as Linux does, every file operation names the
 *       entry relative to the opened directory, so nothing outside the two directories that a link or a swapped
 *       entry leads to is ever read, written, copied or disposed of, and a directory nested in the other is
 *       accepted: once verified, an opened directory stays the one every operation of the poll works in, wherever
 *       a link swapped in for it points.</li>
 *   <li>Elsewhere the same operations are made by path from the pinned locations, and each one resolves its pinned
 *       location again. The directories above the input and move directories must therefore be writable only by
 *       trusted parties, because one of them swapped for a link between the check of a location and an operation
 *       goes undetected. For the same reason a poll is refused, with a WARN, while either directory lies inside the
 *       other: a party that can write only to the outer directory could otherwise swap the inner one for a link
 *       and redirect the operations made in it.</li>
 * </ul>
 *
 * <p>Two limits remain on every platform. The JDK exposes no identity of an open file, so an entry renamed away and
 * back between the checks around an open is read as the file it stood in for, which is still a regular file of the
 * input directory. An entry substituted in the instant between the last check and a rename or delete is renamed or
 * deleted itself, never what it may link to.
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
 * error text, or the poller's own statement of a file's size and the read limit or of a file removed or replaced
 * while it was read, copied or deleted, never a path.
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

    private static final String INPUT_ROLE = "input";

    private static final String MOVE_ROLE = "move";

    /** Options of every read: a link at the file's name fails the open and is never followed. */
    private static final Set<OpenOption> READ_WITHOUT_LINKS =
            Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);

    /** Options of the copy fallback's target: a new file, never one reached through a link at its name. */
    private static final Set<OpenOption> CREATE_WITHOUT_LINKS =
            Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS);

    /** Smallest buffer a read grows to when a file is longer than its size said, as in the JDK's own reads. */
    private static final int READ_BUFFER_SIZE = 8192;

    private static final int COPY_BUFFER_SIZE = 8192;

    private static final FileAttribute<?>[] NO_FILE_ATTRIBUTES = new FileAttribute<?>[0];

    /**
     * Largest file, in bytes, that {@link #readContent(Path, SeekableByteChannel)} reads: the JDK's soft maximum
     * array length ({@code jdk.internal.util.ArraysSupport.SOFT_MAX_ARRAY_LENGTH}), the largest {@code byte[]}
     * {@link Files#readAllBytes(Path)} allocates when it grows a buffer. A longer array may exceed the virtual
     * machine's own limit, and {@link Files#readAllBytes(Path)} throws {@link OutOfMemoryError} for every file longer
     * than {@link Integer#MAX_VALUE}, so a longer file is reported as unreadable before any allocation is attempted.
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

    /** Canonical location of the input directory, pinned at the first poll that resolves it. */
    private final AtomicReference<Path> pinnedInputDirectory = new AtomicReference<>();

    /** Canonical location of the move directory, pinned at the first poll that resolves it. */
    private final AtomicReference<Path> pinnedMoveToDirectory = new AtomicReference<>();

    /**
     * Creates a poller. Relative directories resolve against the working directory, as Mule resolved an endpoint
     * {@code path} against the application directory, and each directory's canonical location is pinned at the
     * first poll that resolves it, for the poller's lifetime.
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
     *         skipped because they were in process, could not be read or were links are not counted
     */
    public int pollOnce() {
        if (!createDirectory(inputDirectory) || (moveToDirectory != null && !createDirectory(moveToDirectory))) {
            return 0;
        }
        Path inputRoot = pinnedLocation(inputDirectory, pinnedInputDirectory, INPUT_ROLE);
        if (inputRoot == null) {
            return 0;
        }
        Path moveRoot = null;
        if (moveToDirectory != null) {
            moveRoot = pinnedLocation(moveToDirectory, pinnedMoveToDirectory, MOVE_ROLE);
            if (moveRoot == null) {
                return 0;
            }
        }

        DirectoryStream<Path> inputStream = openRoot(inputRoot, inputDirectory, INPUT_ROLE);
        if (inputStream == null) {
            return 0;
        }
        DirectoryStream<Path> moveStream = null;
        try {
            if (moveRoot != null) {
                moveStream = openRoot(moveRoot, moveToDirectory, MOVE_ROLE);
                if (moveStream == null) {
                    return 0;
                }
            }
            return poll(inputRoot, inputStream, moveRoot, moveStream);
        } finally {
            close(moveStream, moveToDirectory);
            close(inputStream, inputDirectory);
        }
    }

    /**
     * Reads a file for the handler from the channel the poller opened on it within the input directory, without
     * following a link: the only place a file's bytes are read for the handler. Overridable so tests can simulate a
     * file that cannot be opened or one replaced while it is read. It reads as {@link Files#readAllBytes(Path)} does,
     * taking the size from the open file, and reads a file that grows or shrinks while it is read to its end.
     *
     * @param file    the listed file, as {@link InboundFile#path()} names it
     * @param channel the file, open for reading at its start; the caller closes it
     * @return the file's bytes
     * @throws IOException when the file cannot be read, including a {@link FileSystemException} for {@code file}
     *                     whose reason gives the file's size and {@link #MAX_CONTENT_LENGTH}, never its path, when
     *                     the file is longer than that limit
     */
    protected byte[] readContent(Path file, SeekableByteChannel channel) throws IOException {
        long size = channel.size();
        if (size > MAX_CONTENT_LENGTH) {
            throw new FileSystemException(file.toString(), null,
                    "file size " + size + " bytes exceeds the read limit of " + MAX_CONTENT_LENGTH + " bytes");
        }
        // Left open: closing the stream would close the channel, which the caller owns.
        InputStream source = Channels.newInputStream(channel);
        int capacity = (int) size;
        byte[] buffer = new byte[capacity];
        int length = 0;
        int read;
        while (true) {
            // Reads to the end, which may lie before or after the size taken above.
            while ((read = source.read(buffer, length, capacity - length)) > 0) {
                length += read;
            }
            if (read < 0 || (read = source.read()) < 0) {
                break;
            }
            // One more byte was read: the buffer grows as Files.readAllBytes grows it, up to the read limit.
            if (capacity >= MAX_CONTENT_LENGTH) {
                throw new OutOfMemoryError("Required array size too large");
            }
            capacity = (int) Math.min(Math.max((long) capacity << 1, READ_BUFFER_SIZE), MAX_CONTENT_LENGTH);
            buffer = Arrays.copyOf(buffer, capacity);
            buffer[length++] = (byte) read;
        }
        return capacity == length ? buffer : Arrays.copyOf(buffer, length);
    }

    /**
     * Opens a directory this poller lists or moves files into, at its pinned location. Where the platform's
     * provider returns a {@link SecureDirectoryStream}, as on Linux, every file operation of the poll is made
     * relative to the open directory; otherwise it is made by path from the pinned location, and input and move
     * directories nested one in the other are refused. Package-private so that tests can run the path-based
     * operations on a platform that provides the secure ones.
     *
     * @param directory the pinned canonical location
     * @return the open directory, which the poll closes
     * @throws IOException when the directory cannot be opened
     */
    DirectoryStream<Path> openDirectory(Path directory) throws IOException {
        return Files.newDirectoryStream(directory);
    }

    /**
     * Lists the input directory and processes its regular files once both open directories are verified to be the
     * directories at their pinned locations and, for the path-based operations, neither lies inside the other.
     *
     * @return the number of files handed to the handler
     */
    private int poll(Path inputRoot, DirectoryStream<Path> inputStream, Path moveRoot,
                     DirectoryStream<Path> moveStream) {
        SecureDirectoryStream<Path> secureInput =
                inputStream instanceof SecureDirectoryStream<Path> secureStream ? secureStream : null;
        SecureDirectoryStream<Path> secureMove =
                moveStream instanceof SecureDirectoryStream<Path> secureStream ? secureStream : null;
        // Both directories are worked the same way, so a rename always has a target of its own kind.
        boolean secure = secureInput != null && (moveStream == null || secureMove != null);
        if (!secure && moveRoot != null && isNested(inputRoot, moveRoot)) {
            return 0;
        }
        Root input = secure ? new SecureRoot(inputRoot, secureInput) : new PathRoot(inputRoot);
        Root move = moveRoot == null ? null : secure ? new SecureRoot(moveRoot, secureMove) : new PathRoot(moveRoot);
        if (!isPinnedDirectory(input, inputDirectory, INPUT_ROLE)
                || (move != null && !isPinnedDirectory(move, moveToDirectory, MOVE_ROLE))) {
            return 0;
        }

        // The names are taken first, in the stream's directory-listing order: the receiver sorts only with a
        // configured comparator, and none is. Each entry is examined only when its turn comes.
        List<Path> names = new ArrayList<>();
        try {
            for (Path entry : inputStream) {
                names.add(entry.getFileName());
            }
        } catch (RuntimeException e) {
            // A DirectoryIteratorException carries the I/O failure of a directory read.
            LOG.warn("Could not list input directory {}: {}", safeText(inputDirectory), describe(e, true));
            return 0;
        }

        int handed = 0;
        for (Path name : names) {
            BasicFileAttributes listed;
            try {
                listed = input.attributes(name);
            } catch (IOException | RuntimeException e) {
                // An entry that vanished or cannot be examined is no regular file to poll, so it is skipped silently.
                continue;
            }
            if (listed.isSymbolicLink()) {
                LOG.warn("Skipping symbolic link {}: links are not followed, leaving it in place",
                        safeText(inputDirectory.resolve(name)));
            } else if (listed.isRegularFile() && processFile(input, move, name, listed)) {
                handed++;
            }
        }
        return handed;
    }

    /**
     * Marks, reads, hands over and disposes of one regular file.
     *
     * @param name   the file's name in the input directory
     * @param listed the file's attributes, read without following a link when its turn came
     * @return whether the file was handed to the handler
     */
    private boolean processFile(Root input, Root move, Path name, BasicFileAttributes listed) {
        Path path = inputDirectory.resolve(name);
        Path key = path.toAbsolutePath().normalize();
        if (!inProcess.add(key)) {
            return false;
        }
        try {
            Object identity = identityOf(listed);
            byte[] content;
            try {
                content = uninterruptibly(() -> {
                    try (SeekableByteChannel channel = input.open(name, READ_WITHOUT_LINKS)) {
                        return readContent(path, channel);
                    }
                });
                requireUnchanged(input, name, identity, path, "while being read");
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                // OutOfMemoryError is caught here only: a file whose bytes cannot be allocated, because it is larger
                // than the heap or grew past the read limit while being read, is a file that cannot be read, and the
                // failed allocation holds nothing once unwound.
                LOG.warn("Could not open file {}, leaving it for the next poll: {}", safeText(path), describe(e, true));
                return false;
            }

            try {
                handler.handle(new InboundFile(path, name.toString(), content));
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    // The file is still consumed below; the interrupt is kept for the scheduler that owns the thread.
                    Thread.currentThread().interrupt();
                }
                // Only the exception's type identifiers are logged: the handler parses and forwards file records,
                // so its exception texts can repeat record fields or credentials.
                LOG.error("Exception processing file {}: {}", safeText(name), describe(e, false));
            }

            dispose(input, move, name, path, identity);
            return true;
        } finally {
            inProcess.remove(key);
        }
    }

    /**
     * Consumes a processed file: identical after a completed and after a failed handler. A file removed or replaced
     * since it was read is not consumed, and whatever stands at its name is left untouched.
     */
    private void dispose(Root input, Root move, Path name, Path path, Object identity) {
        Path target = move == null ? null : moveToDirectory.resolve(name);
        BasicFileAttributes current;
        try {
            current = attributesOrNull(input, name);
        } catch (IOException | RuntimeException e) {
            if (target == null) {
                LOG.warn("Failed to delete file {}: {}", safeText(path), describe(e, true));
            } else {
                LOG.warn("Failed to move file {} to {}: {}", safeText(path), safeText(target), describe(e, true));
            }
            return;
        }
        if (current == null) {
            logVanished(path, target);
            return;
        }
        if (!isSameFile(current, identity)) {
            LOG.warn("Leaving file {} in place: it was replaced after it was read, so the replacement is neither"
                    + " moved nor deleted", safeText(path));
            return;
        }

        if (target == null) {
            try {
                input.deleteFile(name);
            } catch (NoSuchFileException e) {
                logVanished(path, null);
            } catch (IOException | RuntimeException e) {
                LOG.warn("Failed to delete file {}: {}", safeText(path), describe(e, true));
            }
            return;
        }

        try {
            rename(input, move, name);
        } catch (IOException | RuntimeException renameFailure) {
            // A rename fails across file stores; the file is then copied and the original deleted.
            try {
                uninterruptibly(() -> {
                    copyAndDelete(input, move, name, path, identity);
                    return null;
                });
            } catch (IOException | RuntimeException copyFailure) {
                LOG.warn("Failed to move file {} to {}: rename failed with {}, copy and delete failed with {}",
                        safeText(path), safeText(target), describe(renameFailure, true), describe(copyFailure, true));
            }
        }
    }

    private static void logVanished(Path path, Path target) {
        if (target == null) {
            LOG.warn("Failed to delete file {}: it no longer exists", safeText(path));
        } else {
            LOG.warn("Failed to move file {} to {}: it no longer exists", safeText(path), safeText(target));
        }
    }

    /**
     * Renames the entry into the move directory under its own name. An existing entry there is replaced, never
     * written through: a directory is removed first, so that a non-empty one fails the rename as a move with
     * {@link StandardCopyOption#REPLACE_EXISTING} fails, and any other entry, a link included, is replaced by the
     * rename itself.
     */
    private static void rename(Root input, Root move, Path name) throws IOException {
        BasicFileAttributes existing = attributesOrNull(move, name);
        if (existing != null && existing.isDirectory()) {
            move.deleteDirectory(name);
        }
        input.renameInto(name, move);
    }

    /**
     * Copies the entry into a new file of the move directory and then deletes it: the fallback of a failed rename.
     * The source is read without following a link, and only while it is still the file that was read; the target
     * is created afresh, never reached through a link, after an existing entry is removed without following it.
     * The copy keeps the source's POSIX permissions where the file system has them, as {@code Files.copy} does.
     */
    private static void copyAndDelete(Root input, Root move, Path name, Path path, Object identity)
            throws IOException {
        try (SeekableByteChannel source = input.open(name, READ_WITHOUT_LINKS)) {
            requireUnchanged(input, name, identity, path, "before it was copied");
            FileAttribute<?>[] permissions = input.permissionsOf(name);
            BasicFileAttributes existing = attributesOrNull(move, name);
            if (existing != null) {
                if (existing.isDirectory()) {
                    move.deleteDirectory(name);
                } else {
                    move.deleteFile(name);
                }
            }
            SeekableByteChannel target = move.open(name, CREATE_WITHOUT_LINKS, permissions);
            try (target) {
                transfer(source, target);
            } catch (IOException | RuntimeException e) {
                // The incomplete copy this call created is removed, as Files.copy removes its own.
                try {
                    move.deleteFile(name);
                } catch (IOException | RuntimeException removalFailure) {
                    e.addSuppressed(removalFailure);
                }
                throw e;
            }
        }
        requireUnchanged(input, name, identity, path, "before it was deleted");
        input.deleteFile(name);
    }

    private static void transfer(SeekableByteChannel source, SeekableByteChannel target) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(COPY_BUFFER_SIZE);
        while (source.read(buffer) >= 0) {
            buffer.flip();
            while (buffer.hasRemaining()) {
                target.write(buffer);
            }
            buffer.clear();
        }
    }

    /**
     * Throws, with a reason that names no path, unless the entry {@code name} is still the regular file
     * {@code identity} identifies.
     *
     * @param when how the reason ends, for example {@code "while being read"}
     */
    private static void requireUnchanged(Root root, Path name, Object identity, Path path, String when)
            throws IOException {
        BasicFileAttributes current = attributesOrNull(root, name);
        if (current == null) {
            throw new FileSystemException(path.toString(), null, "file was removed " + when);
        }
        if (!isSameFile(current, identity)) {
            throw new FileSystemException(path.toString(), null, "file was replaced " + when);
        }
    }

    /** Returns the entry's attributes, read without following a link, or {@code null} when there is no entry. */
    private static BasicFileAttributes attributesOrNull(Root root, Path name) throws IOException {
        try {
            return root.attributes(name);
        } catch (NoSuchFileException e) {
            return null;
        }
    }

    /**
     * Returns a file's identity: its file key or, where the file system reports none, its creation time. Neither
     * changes while the file is written to, so a file still being appended keeps its identity.
     */
    private static Object identityOf(BasicFileAttributes attributes) {
        Object key = attributes.fileKey();
        return key != null ? key : attributes.creationTime();
    }

    /** Whether {@code current} describes the regular file {@code identity} identifies. */
    private static boolean isSameFile(BasicFileAttributes current, Object identity) {
        return current.isRegularFile() && identity.equals(identityOf(current));
    }

    /** One step on file channels. */
    @FunctionalInterface
    private interface ChannelWork<T> {

        T run() throws IOException;
    }

    /**
     * Runs {@code work} with the thread's interrupt status cleared, then restores it. A file channel closes itself
     * when used on an interrupted thread, while a poll goes on after an interrupted handler and keeps the status for
     * the scheduler, so a later file of the same poll is still read, as {@link Files#readAllBytes(Path)} reads
     * without regard to the status.
     */
    private static <T> T uninterruptibly(ChannelWork<T> work) throws IOException {
        boolean interrupted = Thread.interrupted();
        try {
            return work.run();
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Resolves {@code configured} to its canonical location and pins that location at the first poll that resolves
     * it.
     *
     * @return the pinned location, or {@code null}, logged, when {@code configured} cannot be resolved or resolves
     *         elsewhere
     */
    private static Path pinnedLocation(Path configured, AtomicReference<Path> pin, String role) {
        Path canonical;
        try {
            canonical = configured.toRealPath();
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not open {} directory {}: {}", role, safeText(configured), describe(e, true));
            return null;
        }
        pin.compareAndSet(null, canonical);
        Path pinned = pin.get();
        if (!pinned.equals(canonical)) {
            logRelocated(configured, role);
            return null;
        }
        return pinned;
    }

    /** Opens the directory at its pinned location, or returns {@code null} after logging why it could not. */
    private DirectoryStream<Path> openRoot(Path pinned, Path configured, String role) {
        try {
            return openDirectory(pinned);
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not open {} directory {}: {}", role, safeText(configured), describe(e, true));
            return null;
        }
    }

    /**
     * Checks, once the directory is open, that {@code configured} still resolves to the pinned location and that the
     * open directory is the directory standing there, so that a location swapped while it was opened is refused.
     */
    private static boolean isPinnedDirectory(Root root, Path configured, String role) {
        try {
            if (configured.toRealPath().equals(root.location) && root.isOpenedAtLocation()) {
                return true;
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not open {} directory {}: {}", role, safeText(configured), describe(e, true));
            return false;
        }
        logRelocated(configured, role);
        return false;
    }

    /**
     * Checks, for the path-based operations alone, whether one pinned location lies strictly inside the other, and
     * logs why nothing is polled when it does. Made by path, every operation resolves the pinned location again,
     * so a party that can write only to the outer directory could swap the inner one for a link after it was
     * verified and redirect the poll's operations through that link.
     *
     * @param inputRoot the pinned canonical location of the input directory
     * @param moveRoot  the pinned canonical location of the move directory
     * @return whether the directories are nested, so that the poll is refused
     */
    private boolean isNested(Path inputRoot, Path moveRoot) {
        if (inputRoot.equals(moveRoot)) {
            return false;
        }
        if (moveRoot.startsWith(inputRoot)) {
            logNested(MOVE_ROLE, moveToDirectory, INPUT_ROLE, inputDirectory);
            return true;
        }
        if (inputRoot.startsWith(moveRoot)) {
            logNested(INPUT_ROLE, inputDirectory, MOVE_ROLE, moveToDirectory);
            return true;
        }
        return false;
    }

    /**
     * Logs why a poll of nested directories is refused, naming both directories as configured.
     *
     * @param innerRole the role of the directory inside the other
     * @param inner     that directory as configured
     * @param outerRole the role of the directory it lies in
     * @param outer     that directory as configured
     */
    private static void logNested(String innerRole, Path inner, String outerRole, Path outer) {
        LOG.warn("Not polling: the {} directory {} lies inside the {} directory {}, and without a secure directory"
                + " stream a link swapped in for the inner one could redirect the poll, so nothing is read, moved or"
                + " deleted; configure directories that are not nested and restart",
                innerRole, safeText(inner), outerRole, safeText(outer));
    }

    private static void logRelocated(Path configured, String role) {
        LOG.warn("Not polling: the {} directory {} no longer resolves to the directory it resolved to at the first"
                + " poll, so nothing is read, moved or deleted; restart to poll a new location",
                role, safeText(configured));
    }

    private static void close(DirectoryStream<Path> stream, Path configured) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not close directory {}: {}", safeText(configured), describe(e, true));
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
     * A directory a poll works in, open at its pinned location. Every operation names an entry of the directory by
     * its file name alone and never follows a symbolic link at that entry.
     */
    private abstract static class Root {

        /** The pinned canonical location. */
        final Path location;

        Root(Path location) {
            this.location = location;
        }

        /** Whether the directory standing at {@link #location}, which is no link, is the open directory. */
        abstract boolean isOpenedAtLocation() throws IOException;

        /** Reads the entry's attributes without following a link; {@link NoSuchFileException} when there is none. */
        abstract BasicFileAttributes attributes(Path name) throws IOException;

        abstract SeekableByteChannel open(Path name, Set<OpenOption> options, FileAttribute<?>... attributes)
                throws IOException;

        /** Removes the entry; a link is removed itself. */
        abstract void deleteFile(Path name) throws IOException;

        /** Removes the entry, an empty directory. */
        abstract void deleteDirectory(Path name) throws IOException;

        /**
         * Renames the entry into {@code target}, of the same kind, under the same name, replacing an entry there that
         * is not a directory; fails across file stores.
         */
        abstract void renameInto(Path name, Root target) throws IOException;

        /** Returns the entry's POSIX attribute view, read without following a link, or {@code null} without one. */
        abstract PosixFileAttributeView posixView(Path name);

        /** Returns the entry's permissions as a creation attribute, or none where the file system has none. */
        final FileAttribute<?>[] permissionsOf(Path name) throws IOException {
            PosixFileAttributeView view = posixView(name);
            if (view == null) {
                return NO_FILE_ATTRIBUTES;
            }
            return new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(view.readAttributes().permissions())};
        }
    }

    /** A directory open as a {@link SecureDirectoryStream}: every operation is relative to the open directory. */
    private static final class SecureRoot extends Root {

        private final SecureDirectoryStream<Path> directory;

        SecureRoot(Path location, SecureDirectoryStream<Path> directory) {
            super(location);
            this.directory = directory;
        }

        @Override
        boolean isOpenedAtLocation() throws IOException {
            BasicFileAttributes opened = directory.getFileAttributeView(BasicFileAttributeView.class).readAttributes();
            BasicFileAttributes standing =
                    Files.readAttributes(location, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return standing.isDirectory() && Objects.equals(opened.fileKey(), standing.fileKey());
        }

        @Override
        BasicFileAttributes attributes(Path name) throws IOException {
            return directory.getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                    .readAttributes();
        }

        @Override
        SeekableByteChannel open(Path name, Set<OpenOption> options, FileAttribute<?>... attributes)
                throws IOException {
            return directory.newByteChannel(name, options, attributes);
        }

        @Override
        void deleteFile(Path name) throws IOException {
            directory.deleteFile(name);
        }

        @Override
        void deleteDirectory(Path name) throws IOException {
            directory.deleteDirectory(name);
        }

        @Override
        void renameInto(Path name, Root target) throws IOException {
            directory.move(name, ((SecureRoot) target).directory, name);
        }

        @Override
        PosixFileAttributeView posixView(Path name) {
            return directory.getFileAttributeView(name, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        }
    }

    /**
     * A directory reached by path from its pinned location, where the platform provides no secure directory stream:
     * a link at the entry is still never followed, while every operation resolves the pinned location, and each
     * directory above it, again. A directory above the location swapped for a link after the location was checked
     * therefore redirects the operation, so those directories must be writable only by trusted parties; for the
     * same reason the poll refuses input and move directories nested one in the other, because the outer one may
     * be writable by parties the application does not trust.
     */
    private static final class PathRoot extends Root {

        PathRoot(Path location) {
            super(location);
        }

        @Override
        boolean isOpenedAtLocation() throws IOException {
            return Files.readAttributes(location, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isDirectory();
        }

        @Override
        BasicFileAttributes attributes(Path name) throws IOException {
            return Files.readAttributes(location.resolve(name), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        }

        @Override
        SeekableByteChannel open(Path name, Set<OpenOption> options, FileAttribute<?>... attributes)
                throws IOException {
            return Files.newByteChannel(location.resolve(name), options, attributes);
        }

        @Override
        void deleteFile(Path name) throws IOException {
            Files.delete(location.resolve(name));
        }

        @Override
        void deleteDirectory(Path name) throws IOException {
            Files.delete(location.resolve(name));
        }

        @Override
        void renameInto(Path name, Root target) throws IOException {
            // An atomic move is a plain rename: it replaces a file or link at the target and never copies.
            Files.move(location.resolve(name), target.location.resolve(name), StandardCopyOption.ATOMIC_MOVE);
        }

        @Override
        PosixFileAttributeView posixView(Path name) {
            return Files.getFileAttributeView(location.resolve(name), PosixFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
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
     *                   system's error text and the poller with a file's size and the read limit or with the
     *                   statement that a file was removed or replaced, never with the path; only the poller's own
     *                   file operations pass {@code true}, because a handler may construct such an exception with
     *                   any text
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
