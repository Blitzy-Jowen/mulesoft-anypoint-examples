/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.importing_an_email_attachment_using_the_imap_connector.service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.stereotype.Service;

/**
 * Writes a message payload to {@code <dir>/<name>} the way a Mule 3.8 {@code file:outbound-endpoint} on the default
 * file connector did: shared component SC-6 of the conversion plan.
 *
 * <ul>
 *   <li>The target directory is created, with any missing parents, before the write, and so is every missing
 *       directory the name's own path segments lead through to the file, as Mule's {@code FileUtils.createFile}
 *       created the parent directories of the file it was given.</li>
 *   <li>An existing file of the same name is replaced as a whole by the new content. None of the originals
 *       declares a {@code file:connector}, so {@code outputAppend} keeps its default {@code false}.</li>
 *   <li>The replacement is whole-file wherever the directory admits the new entry and the rename: the content is
 *       written in full to a hidden file {@code .<uuid>.tmp} beside the target, which is then renamed over the
 *       target atomically. A reader, in this process or another, therefore sees either the old or the new whole
 *       file, never an empty or partial one, and a write that fails leaves the existing file untouched and removes
 *       its temporary file.</li>
 *   <li>The temporary file that replaces an existing file is created readable and writable by its owner only, so
 *       no other user can read the new content while it is written, and gets the replaced file's POSIX
 *       permissions just before it is renamed into place. A new file gets the permissions an ordinary create
 *       gives it.</li>
 *   <li>Whole-file replacement installs a new file, owned by the writing process. Of the replaced file only the
 *       name and the POSIX permissions carry over: its owner, group, ACLs and extended attributes do not, and
 *       another hard link to it keeps the old content.</li>
 *   <li>A file the process may not write is refused, the failure caused by an {@link AccessDeniedException}, as an
 *       in-place write refused it, although the rename itself needs write permission on the directory only.</li>
 *   <li>Where the directory refuses the temporary file, typically because the process may not write it, or refuses
 *       the rename over an existing file, as a sticky directory such as {@code /tmp} does for a file another user
 *       owns, a regular file is not replaced as a whole: an existing file the process may write is overwritten in
 *       place instead, as Mule overwrote it, under the same lock and with no temporary file left. It keeps its
 *       inode, owner and mode, but the write is not atomic: its readers can see it empty or partly written, and a
 *       write that fails part-way leaves it so. A new file in a directory that refuses its temporary file fails,
 *       the failure caused by an {@link AccessDeniedException} naming it, as its in-place create failed, and
 *       nothing is created.</li>
 *   <li>An existing target that is neither a regular file nor a directory, such as a named pipe or a device, is
 *       not replaced but written in place, as Mule wrote it, under the same lock and with no temporary file.</li>
 *   <li>Writes to the same file are serialised within the JVM by a lock per destination, keyed by its absolute,
 *       normalised path and shared by every instance, so simultaneous writers of one name never interleave and
 *       the last one to finish wins.</li>
 *   <li>A symbolic link at the target is followed, as an in-place write did: the linked file is replaced, or
 *       created when the link dangles, and the link itself is kept.</li>
 *   <li>A target that resolves to a directory, for example the empty name, fails, the failure caused by a
 *       {@link FileSystemException} with the reason {@code Is a directory}.</li>
 *   <li>Callers resolve the endpoint's {@code outputPattern} themselves and pass the resulting name; this class
 *       evaluates no pattern. An endpoint without an {@code outputPattern} used Mule's default name, which
 *       {@link #defaultFileName()} reproduces.</li>
 *   <li>Names are not sanitised: callers pass trusted, already resolved names, as the Mule endpoint received
 *       them. A name is joined to the directory as Mule's file connector joined them, {@code address + "/" +
 *       filename}: the separators it starts with are dropped and the rest is resolved against the directory, so an
 *       absolute name such as {@code /etc/qa.txt} names {@code <dir>/etc/qa.txt}, while every other segment,
 *       {@code ..} included, is kept as given and a name that climbs out of the directory is written where it
 *       leads.</li>
 *   <li>A relative directory resolves against the working directory, as Mule's relative {@code path} did.</li>
 *   <li>Every failure that ends a write, an {@link IOException} of a directory, a link, the temporary file, the
 *       rename or the in-place write, or the {@link InvalidPathException} of a name that is no valid path, is
 *       reported as an {@link IOException} with the message {@code Streaming failed. Could not get output stream},
 *       Mule's {@code CoreMessages.streamingFailedNoStream()} with which {@code FileConnector} wrapped it, and the
 *       failure, with its suppressed exceptions, as the cause. The message names no path, so a caller that shows it
 *       discloses neither the directory nor a temporary file. A refused rename that the in-place overwrite then
 *       completes is not a failure and is not reported; when the overwrite fails too, its exception is the cause,
 *       with the refused rename as a suppressed exception. A {@code null} argument is the
 *       {@link NullPointerException} naming it, before anything is created.</li>
 * </ul>
 *
 * <p>Copy rule: this project is the first consumer, so this class is the reference implementation. Every other
 * project with a {@code file:outbound-endpoint} carries a copy of it, and of its unit test, differing in the
 * {@code package} line alone; it therefore depends on the JDK and Spring's {@link Service} only, and never on a
 * project type.
 *
 * <p>Instances are safe to share between threads, and separate instances coordinate with each other, because the
 * per-destination locks belong to the class.
 */
@Service
public class FileOutboundWriter {

    /** The symbolic-link depth at which resolution stops, the limit Linux applies to a path lookup. */
    private static final int MAX_SYMLINK_HOPS = 40;

    /**
     * The message of the {@link IOException} every failed write is reported with: Mule 3.8.0's
     * {@code CoreMessages.streamingFailedNoStream()} (core message 205), with which {@code FileConnector} wrapped each
     * {@link IOException} of an outbound file write in a {@code DispatchException}. It names no path, so a caller that
     * shows the message discloses neither the directory nor a temporary file.
     */
    private static final String WRITE_FAILED_MESSAGE = "Streaming failed. Could not get output stream";

    /**
     * The mode the temporary file replacing an existing file is created with, readable and writable by its owner
     * only. It is applied by the call that creates the file, and the process's umask can only narrow it.
     */
    private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));

    /**
     * Locks of the destinations being written, keyed by absolute, normalised path. An entry exists only while a
     * writer holds or awaits it, so the map does not grow with every distinct file name.
     */
    private static final ConcurrentMap<Path, TargetLock> LOCKS = new ConcurrentHashMap<>();

    /** The observer each temporary file is shown to once it holds the content, or {@code null} for none. */
    private final TemporaryFileObserver temporaryFileObserver;

    /**
     * Creates a writer, with no temporary-file observer; every instance shares the class's per-destination locks, so
     * one instance or several serve callers alike.
     */
    public FileOutboundWriter() {
        this.temporaryFileObserver = null;
    }

    /**
     * Creates a writer that shows {@code temporaryFileObserver} the temporary file of each whole-file write once it
     * holds the content, before it takes the replaced file's POSIX permissions and is renamed into place; it shares
     * the class's per-destination locks like every instance. Package-private for the unit test only.
     *
     * @param temporaryFileObserver the observer of each temporary file
     */
    FileOutboundWriter(TemporaryFileObserver temporaryFileObserver) {
        this.temporaryFileObserver = Objects.requireNonNull(temporaryFileObserver, "temporaryFileObserver");
    }

    /**
     * Writes {@code content} to {@code dir/name}, creating {@code dir} and then the directories between it and the
     * file when missing, and replacing any existing file as a whole. {@code name} is joined to {@code dir} as Mule
     * joined {@code address + "/" + filename}: its leading separators are dropped and the rest is resolved against
     * {@code dir}, every {@code ..} segment kept. The content goes to a temporary file beside the destination,
     * owner-only while it receives the content, which then gets the replaced file's POSIX permissions and is renamed
     * over it atomically, under a lock that serialises writers of the same destination within the JVM. A new file
     * gets the permissions an ordinary create gives it. A symbolic link at {@code dir/name} is followed and the file
     * it links to is the one replaced or created. An existing file that is neither a regular file nor a directory is
     * written in place, and so is an existing file in a directory that refuses the process the temporary file or its
     * rename, as a sticky directory refuses the rename over a file another user owns; readers can then see it
     * part-written. A writer created with a temporary-file observer shows it the temporary file once it holds the
     * content, and an {@link IOException} of the observer ends the write as a failed write does.
     *
     * @param dir     the target directory, created with its missing parents
     * @param name    the resolved output file name, a leading separator not taking it out of {@code dir}
     * @param content the bytes to write, exactly as given
     * @return the written file, {@code dir} resolved with {@code name} less its leading separators
     * @throws NullPointerException when {@code dir}, {@code name} or {@code content} is {@code null}, naming it
     * @throws IOException          with the message {@code Streaming failed. Could not get output stream} whenever
     *                              the write fails, its cause the failure itself: an {@link AccessDeniedException}
     *                              when the existing file is one the process may not write, which is left as it
     *                              was, or when the file is new and its directory does not let the process create
     *                              it; an {@link InvalidPathException} when {@code name} is no valid path; otherwise
     *                              the {@link IOException} of the directory that cannot be created or the file that
     *                              cannot be written. The existing file is left as it was, unless it was being
     *                              written in place
     */
    public Path write(Path dir, String name, byte[] content) throws IOException {
        Objects.requireNonNull(dir, "dir");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(content, "content");
        try {
            Files.createDirectories(dir);
            Path target = join(dir, name);
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path destination = followLinks(target);
            Path key = destination.toAbsolutePath().normalize();
            TargetLock entry = LOCKS.compute(key, (path, existing) -> {
                TargetLock held = existing == null ? new TargetLock() : existing;
                held.holders++;
                return held;
            });
            try {
                entry.lock.lock();
                try {
                    replace(destination, content, temporaryFileObserver);
                } finally {
                    entry.lock.unlock();
                }
            } finally {
                LOCKS.computeIfPresent(key, (path, held) -> --held.holders == 0 ? null : held);
            }
            return target;
        } catch (IOException | InvalidPathException failure) {
            throw new IOException(WRITE_FAILED_MESSAGE, failure);
        }
    }

    /**
     * Writes {@code content} encoded as UTF-8 to {@code dir/name}; see {@link #write(Path, String, byte[])}.
     *
     * @param dir     the target directory, created with its missing parents
     * @param name    the resolved output file name, a leading separator not taking it out of {@code dir}
     * @param content the text to write, encoded as UTF-8
     * @return the written file, {@code dir} resolved with {@code name} less its leading separators
     * @throws IOException with the message {@code Streaming failed. Could not get output stream} when a directory
     *                     cannot be created or the file cannot be written, its cause the failure itself
     */
    public Path write(Path dir, String name, String content) throws IOException {
        Objects.requireNonNull(content, "content");
        return write(dir, name, content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Writes {@code content} to {@code dir/name} for a directory given as configured, absolute or relative to the
     * working directory; see {@link #write(Path, String, byte[])}.
     *
     * @param dir     the target directory path, created with its missing parents
     * @param name    the resolved output file name, a leading separator not taking it out of {@code dir}
     * @param content the bytes to write, exactly as given
     * @return the written file, {@code Path.of(dir)} resolved with {@code name} less its leading separators
     * @throws IOException with the message {@code Streaming failed. Could not get output stream} when a directory
     *                     cannot be created or the file cannot be written, its cause the failure itself
     */
    public Path write(String dir, String name, byte[] content) throws IOException {
        Objects.requireNonNull(dir, "dir");
        return write(Path.of(dir), name, content);
    }

    /**
     * Writes {@code content} encoded as UTF-8 to {@code dir/name} for a directory given as configured, absolute or
     * relative to the working directory; see {@link #write(Path, String, byte[])}.
     *
     * @param dir     the target directory path, created with its missing parents
     * @param name    the resolved output file name, a leading separator not taking it out of {@code dir}
     * @param content the text to write, encoded as UTF-8
     * @return the written file, {@code Path.of(dir)} resolved with {@code name} less its leading separators
     * @throws IOException with the message {@code Streaming failed. Could not get output stream} when a directory
     *                     cannot be created or the file cannot be written, its cause the failure itself
     */
    public Path write(String dir, String name, String content) throws IOException {
        Objects.requireNonNull(dir, "dir");
        return write(Path.of(dir), name, content);
    }

    /**
     * Returns the name Mule's file connector gave an outbound file when the endpoint declared no
     * {@code outputPattern}: a random UUID followed by {@code .dat} ({@code #[function:uuid].dat}), so every call
     * yields a new file.
     *
     * @return a new {@code <uuid>.dat} file name
     */
    public static String defaultFileName() {
        return UUID.randomUUID() + ".dat";
    }

    /**
     * Joins {@code name} to {@code dir} as Mule's file connector joined its endpoint address and file name,
     * {@code address + "/" + filename}, which {@link java.io.File} normalised by collapsing the doubled separator: the
     * separators {@code name} starts with, {@code /} and the file system's own, are dropped and the rest is resolved
     * against {@code dir}. A name that is an absolute path therefore names a file under {@code dir}, and every other
     * character of the name, {@code ..} segments included, is kept as given.
     *
     * @param dir  the target directory
     * @param name the resolved output file name
     * @return {@code dir} resolved with {@code name} less its leading separators
     * @throws InvalidPathException when the rest of {@code name} is not a valid path, for example when it holds a NUL
     *                              character
     */
    private static Path join(Path dir, String name) {
        String separator = dir.getFileSystem().getSeparator();
        int start = 0;
        while (start < name.length()) {
            if (name.charAt(start) == '/') {
                start++;
            } else if (!separator.isEmpty() && name.startsWith(separator, start)) {
                start += separator.length();
            } else {
                break;
            }
        }
        return dir.resolve(name.substring(start));
    }

    /**
     * Follows the chain of symbolic links starting at {@code target} to the file a write through it reaches. Link
     * text that is relative resolves against the link's own directory; the destination need not exist.
     *
     * @param target the path the caller named
     * @return {@code target} itself when it is not a symbolic link, otherwise the end of its link chain
     * @throws IOException when a link cannot be read, or the chain is longer than {@value #MAX_SYMLINK_HOPS} links
     */
    private static Path followLinks(Path target) throws IOException {
        Path destination = target;
        for (int hops = 0; Files.isSymbolicLink(destination); hops++) {
            if (hops == MAX_SYMLINK_HOPS) {
                throw new FileSystemException(target.toString(), null, "Too many levels of symbolic links");
            }
            destination = destination.resolveSibling(Files.readSymbolicLink(destination));
        }
        return destination;
    }

    /**
     * Writes {@code content} to {@code destination}; the caller holds the destination's lock. A regular file, or a
     * missing one, is replaced as a whole: the content is written to a new hidden file in the destination's
     * directory, created by {@link #createTemporaryFile(Path, Path)} before any content reaches it, shown to
     * {@code observer} once it holds the whole content and is closed, then given the existing file's POSIX
     * permissions and renamed over the destination. When the directory refuses to create the
     * temporary file with an {@link AccessDeniedException}, before any content is written, the destination is
     * written in place by {@link #writeInPlace(Path, byte[])} instead, as Mule wrote it: an existing file the
     * process may write is overwritten, keeping its inode, owner and mode but not atomically for its readers, and a
     * new file fails as its in-place create fails. When the rename fails with an {@link IOException}, as a sticky
     * directory refuses it over a file another user owns, the temporary file is removed and a destination that is
     * still an existing regular file is written in place the same way: the in-place write's outcome is the one
     * reported, a failure of it carrying the refused rename as a suppressed exception, while the refused rename of a
     * new file is thrown unchanged. On any other failure, a failure of {@code observer} included, the temporary file
     * is removed and the destination is left as it was. An existing file that is neither a regular file nor a
     * directory, such as a named pipe or a device, is written in place the same way.
     *
     * @param destination the file to replace or create, never a symbolic link
     * @param content     the bytes the file must hold
     * @param observer    the observer of the temporary file, or {@code null} for none
     * @throws AccessDeniedException when the destination is a regular file the process may not write, or a new file
     *                               its directory does not let the process create; no file is created
     * @throws IOException           when the destination is a directory, the file cannot be written, a new file
     *                               cannot be renamed into place, or {@code observer} throws it
     */
    private static void replace(Path destination, byte[] content, TemporaryFileObserver observer)
            throws IOException {
        if (Files.isDirectory(destination)) {
            throw new FileSystemException(destination.toString(), null, "Is a directory");
        }
        if (Files.isRegularFile(destination)) {
            if (!Files.isWritable(destination)) {
                throw new AccessDeniedException(destination.toString());
            }
        } else if (Files.exists(destination)) {
            writeInPlace(destination, content);
            return;
        }
        Path temp = destination.resolveSibling("." + UUID.randomUUID() + ".tmp");
        SeekableByteChannel channel;
        try {
            channel = createTemporaryFile(temp, destination);
        } catch (AccessDeniedException directoryRefusesNewEntry) {
            writeInPlace(destination, content);
            return;
        } catch (IOException | RuntimeException | Error failure) {
            discard(temp, failure);
            throw failure;
        }
        try {
            try (channel) {
                ByteBuffer remaining = ByteBuffer.wrap(content);
                while (remaining.hasRemaining()) {
                    channel.write(remaining);
                }
            }
            if (observer != null) {
                observer.contentWritten(temp);
            }
            copyPosixPermissions(destination, temp);
        } catch (IOException | RuntimeException | Error failure) {
            discard(temp, failure);
            throw failure;
        }
        try {
            moveIntoPlace(temp, destination);
        } catch (IOException refused) {
            discard(temp, refused);
            if (!Files.isRegularFile(destination)) {
                throw refused;
            }
            try {
                writeInPlace(destination, content);
            } catch (IOException | RuntimeException | Error inPlaceFailure) {
                inPlaceFailure.addSuppressed(refused);
                throw inPlaceFailure;
            }
        } catch (RuntimeException | Error failure) {
            discard(temp, failure);
            throw failure;
        }
    }

    /**
     * Writes {@code content} to {@code destination} in place, as Mule's file connector wrote every file: the file is
     * opened to be created, truncated and written, so an existing one keeps its inode, owner and mode, and a reader
     * can see it empty or partly written until the write completes. The caller holds the destination's lock.
     *
     * @param destination the file to overwrite or create, never a symbolic link
     * @param content     the bytes the file must hold
     * @throws AccessDeniedException when the process may not write the file, or may not create it in its directory
     * @throws IOException           when the file cannot be opened or written
     */
    private static void writeInPlace(Path destination, byte[] content) throws IOException {
        Files.write(destination, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }

    /**
     * Creates the empty temporary file {@code temp} that will replace {@code destination} and opens it for writing.
     * When {@code destination} is an existing regular file on a file store with POSIX permissions, {@code temp} is
     * created readable and writable by its owner only, by the same call that creates it, so no other user can read
     * the content written to it next, whatever the replaced file's mode; it gets that file's permissions only once
     * the content is complete. Otherwise {@code temp} is created as an ordinary create creates a file, so a new file
     * gets the permissions the process's umask gives it. Package-private for the unit test only.
     *
     * @param temp        the temporary file to create, which must not exist
     * @param destination the file {@code temp} will replace, which may not exist yet
     * @return a channel writing to the empty {@code temp} from its start
     * @throws IOException when {@code temp} exists already or cannot be created
     */
    static SeekableByteChannel createTemporaryFile(Path temp, Path destination) throws IOException {
        Set<StandardOpenOption> options = EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        if (hasPosixPermissions(destination)) {
            return Files.newByteChannel(temp, options, OWNER_ONLY);
        }
        return Files.newByteChannel(temp, options);
    }

    /**
     * Gives {@code temp} the POSIX permissions of an existing regular {@code destination}, so the replaced file
     * keeps its mode as an in-place overwrite did. Nothing is copied for a new file or on a file store without
     * POSIX permissions; a destination another process removed while the content was written leaves {@code temp}
     * owner-only.
     *
     * @param destination the file being replaced, which may not exist yet
     * @param temp        the file that will replace it
     * @throws IOException when the permissions cannot be read or set
     */
    private static void copyPosixPermissions(Path destination, Path temp) throws IOException {
        if (hasPosixPermissions(destination)) {
            Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(destination));
        }
    }

    /**
     * Tells whether {@code destination} is an existing regular file whose POSIX permissions its replacement takes.
     *
     * @param destination the file being replaced, which may not exist yet
     * @return {@code true} for an existing regular file on a file store with POSIX permissions
     * @throws IOException when the file store of {@code destination} cannot be determined
     */
    private static boolean hasPosixPermissions(Path destination) throws IOException {
        return Files.isRegularFile(destination)
                && Files.getFileStore(destination).supportsFileAttributeView(PosixFileAttributeView.class);
    }

    /**
     * Renames {@code temp} over {@code destination} atomically, falling back to a replacing move on a file system
     * that offers no atomic rename.
     *
     * @param temp        the fully written file
     * @param destination the file it replaces
     * @throws IOException when the move fails
     */
    private static void moveIntoPlace(Path temp, Path destination) throws IOException {
        try {
            Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Removes the temporary file of a failed write, recording a failure to do so on the write's own failure so
     * that the original cause is the one the caller receives.
     *
     * @param temp    the temporary file, which may not have been created
     * @param failure the failure that ended the write
     */
    private static void discard(Path temp, Throwable failure) {
        try {
            Files.deleteIfExists(temp);
        } catch (IOException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    /**
     * Observes the temporary file of a whole-file write once it holds the content. Package-private for the unit test
     * only.
     */
    interface TemporaryFileObserver {

        /**
         * Called in the writing thread, under the destination's lock, once the temporary file that will replace or
         * create the destination holds the whole content and its channel is closed, before it takes the replaced
         * file's POSIX permissions and is renamed into place. A failure it throws ends the write like any write
         * failure: the temporary file is removed, the destination is left as it was, and an {@link IOException} is
         * the cause of the writer's {@code Streaming failed. Could not get output stream} while any other failure
         * reaches the caller unchanged.
         *
         * @param temp the temporary file, holding the whole content
         * @throws IOException to end the write with this failure
         */
        void contentWritten(Path temp) throws IOException;
    }

    /**
     * A destination's lock with the count of writers holding or awaiting it. The count changes only inside
     * {@link ConcurrentHashMap#compute} and {@link ConcurrentHashMap#computeIfPresent} on {@link #LOCKS}, which
     * run atomically per key, and the entry is removed when it drops to zero.
     */
    private static final class TargetLock {

        /** Serialises the writers of one destination. */
        private final ReentrantLock lock = new ReentrantLock();

        /** Writers holding or awaiting {@link #lock}. */
        private int holders;
    }
}
