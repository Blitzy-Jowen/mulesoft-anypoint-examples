/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_expression_language_basics.service;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.ClosedDirectoryStreamException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

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
 *       target. Where the file system supports an atomic rename, the rename is atomic, so a reader, in this process
 *       or another, sees either the old or the new whole file, never an empty or partial one, and a write that fails
 *       leaves the existing file untouched and removes its temporary file. Only where the writer works on path
 *       names, because directory-relative operations are unavailable, and the file system offers no atomic rename
 *       either, does it fall back to a replacing move, which is not atomic: while it runs a reader can see the
 *       target missing or incomplete, and a move that fails can leave the target incomplete or remove the existing
 *       file; the temporary file is then removed and, as after a refused rename, a regular file left at the target
 *       is overwritten in place, while with none left the write fails.</li>
 *   <li>The temporary file that replaces an existing file is created readable and writable by its owner only, so
 *       no other user can read the new content while it is written, and gets the replaced file's POSIX
 *       permissions just before it is renamed into place. A new file gets the permissions an ordinary create
 *       gives it. The temporary file's identity is read once it is created and checked again before it takes
 *       those permissions and is renamed: an entry swapped in for it in between is neither re-moded nor
 *       installed, but removed, and the write fails.</li>
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
 *   <li>Writes to the same file are serialised within the JVM by a lock per destination, keyed by the identity of
 *       the directory holding it, its file key or, on a file system without file keys, its real path, together
 *       with the file's name, and shared by every instance, so simultaneous writers of one file, whichever
 *       contained link they reach it through, never interleave and the last one to finish wins.</li>
 *   <li>Symbolic links are confined to the target directory. A link at the target, or in place of a directory the
 *       name leads through, is followed only when it lies inside the directory and its text leads to an entry
 *       inside it: relative text continues from the link's own directory and may not climb above the target
 *       directory with {@code ..}, and absolute text must start with the target directory's real path, or with
 *       its absolute path when that holds no {@code .} or {@code ..} segment. A followed link at the target is
 *       kept, and the file it leads to is replaced, or created when the link dangles. Once the name's own
 *       {@code ..} segments have taken the walk above the directory, no link is followed. A link that is not
 *       confined fails the write before anything outside the directory is created, written, re-moded or removed,
 *       the failure caused by a {@link FileSystemException} naming the link with the reason
 *       {@value #LINK_NOT_CONFINED}; Mule followed every link. More than {@value #MAX_SYMLINK_HOPS} links on one
 *       write fail it as a Linux path lookup fails, with the reason {@value #TOO_MANY_LINKS}.</li>
 *   <li>On a file system where {@link Files#newDirectoryStream(Path)} returns a {@link SecureDirectoryStream}, as
 *       it does on Linux, every directory on the walk is held open, from the target directory on, and every entry,
 *       a missing directory included, is checked, created and acted on relative to the open directory the walk
 *       reached, never through a link it did not check: a link swapped in for a checked directory, for the
 *       destination or for the temporary file is not followed, and a directory moved away during the write is
 *       still the one written to. The walk keeps every directory it entered open, so a {@code ..} below the target
 *       directory returns to that directory rather than to wherever it has since been moved. The JDK has no
 *       directory-relative directory creation, so a missing directory is created through the entry
 *       {@code /proc/self/fd/<n>} that the kernel resolves to the open directory itself, never by the path of that
 *       directory, and a write whose missing directory no such entry can be found for fails, the failure caused by
 *       a {@link FileSystemException} naming that directory with the reason {@value #NOT_CREATED_RELATIVE}.
 *       Holding a directory open needs read permission on it, so a directory on the walk that the process may
 *       search but not read cannot be held open and fails the write before anything is created in it, the failure
 *       caused by an {@link AccessDeniedException} naming the directory; Mule wrote into such a directory. Only
 *       where the file system offers no {@link SecureDirectoryStream}, as the JDK 17 offers none on macOS and
 *       Windows, does the writer work on path names, with {@link LinkOption#NOFOLLOW_LINKS} and the same static
 *       checks but without protection against a link swapped in for a directory on the path during the write,
 *       between a check and the operation it guards. The target directory is opened with
 *       {@link Files#newDirectoryStream(Path)} there too, so a target directory the process may not read fails the
 *       write on every platform.</li>
 *   <li>A target that resolves to a directory, for example the empty name or a contained link to a directory,
 *       fails, the failure caused by a {@link FileSystemException} with the reason {@value #IS_A_DIRECTORY}.</li>
 *   <li>Callers resolve the endpoint's {@code outputPattern} themselves and pass the resulting name; this class
 *       evaluates no pattern. An endpoint without an {@code outputPattern} used Mule's default name, which
 *       {@link #defaultFileName()} reproduces.</li>
 *   <li>Names are not sanitised: callers pass trusted, already resolved names, as the Mule endpoint received
 *       them. A name is joined to the directory as Mule's file connector joined them, {@code address + "/" +
 *       filename}: the separators it starts with are dropped and the rest is resolved against the directory, so an
 *       absolute name such as {@code /etc/qa.txt} names {@code <dir>/etc/qa.txt}, while every other segment,
 *       {@code ..} included, is kept as given and a name that climbs out of the directory is written where it
 *       leads. Only the symbolic links the walk meets are confined, as above; the name's own segments are
 *       not.</li>
 *   <li>A relative directory resolves against the working directory, as Mule's relative {@code path} did.</li>
 *   <li>Every failure that ends a write, an {@link IOException} of a directory, a link, a refused link, the
 *       temporary file, the rename or the in-place write, or the {@link InvalidPathException} of a name that is no
 *       valid path, is reported as an {@link IOException} with the message
 *       {@code Streaming failed. Could not get output stream}, Mule's {@code CoreMessages.streamingFailedNoStream()}
 *       with which {@code FileConnector} wrapped it, and the failure, with its suppressed exceptions, as the cause.
 *       The message names no path, so a caller that shows it discloses neither the directory nor a temporary file.
 *       An exception of a directory-relative operation names the entry by its full path, as the same operation on
 *       path names names it. A refused rename that the in-place overwrite then completes is not a failure and is
 *       not reported; when the overwrite fails too, its exception is the cause, with the refused rename as a
 *       suppressed exception. A {@code null} argument is the {@link NullPointerException} naming it, before
 *       anything is created.</li>
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

    /** The reason of the failure ending a write whose symbolic links number more than {@link #MAX_SYMLINK_HOPS}. */
    private static final String TOO_MANY_LINKS = "Too many levels of symbolic links";

    /** The reason of the failure ending a write whose name resolves to a directory. */
    private static final String IS_A_DIRECTORY = "Is a directory";

    /**
     * The reason of the failure ending a write through a symbolic link that lies outside the target directory, or
     * whose text leads out of it.
     */
    private static final String LINK_NOT_CONFINED = "Symbolic link is not confined to the target directory";

    /**
     * The reason of the failure ending a write on path names when the path of a directory the walk entered no longer
     * leads to that directory, so a missing directory cannot be created in it by path name.
     */
    private static final String DIRECTORY_REPLACED = "Directory was replaced while the file was being written";

    /**
     * The reason of the failure ending a write when a missing directory cannot be created relative to the open
     * directory that is to hold it, because no open file descriptor of the process can be found to name that
     * directory.
     */
    private static final String NOT_CREATED_RELATIVE = "Directory cannot be created relative to the open directory";

    /**
     * The directory listing the open file descriptors of this process. Each entry is a link that the kernel resolves
     * to the open file itself rather than to the path it was opened by, so a path through an entry that names an open
     * directory leads into that directory, wherever it has been moved since and whatever now stands at its path.
     */
    private static final Path OPEN_DESCRIPTORS = Path.of("/proc/self/fd");

    /**
     * Keeps every directory held open by a {@link SecureHandle} from being closed while a missing directory is
     * created through an open file descriptor: {@link SecureHandle#createDirectory(Path)} holds it shared from the
     * choice of the descriptor until the directory is created, and {@link SecureHandle#close()} holds it exclusively,
     * so a descriptor another writer holds on the same directory cannot be closed, and its number reused for another
     * file, in between.
     */
    private static final ReadWriteLock HELD_DIRECTORIES = new ReentrantReadWriteLock();

    /**
     * The reason of the failure ending a write whose temporary file is no longer the file the writer created, so it
     * is neither given the replaced file's permissions nor renamed into place.
     */
    private static final String TEMPORARY_FILE_REPLACED = "Temporary file was replaced while it was being written";

    /** How the temporary file is opened: created, never over an existing entry or through a link, for writing. */
    private static final Set<OpenOption> TEMPORARY_FILE_OPTIONS =
            Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);

    /** How a file written in place is opened: created when missing, truncated, for writing, never through a link. */
    private static final Set<OpenOption> IN_PLACE_OPTIONS = Set.of(StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);

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
     * Locks of the destinations being written, keyed by the identity of the directory holding the destination and
     * its name. An entry exists only while a writer holds or awaits it, so the map does not grow with every distinct
     * file name.
     */
    private static final ConcurrentMap<LockKey, TargetLock> LOCKS = new ConcurrentHashMap<>();

    /** How the writer reaches the directories it walks. */
    private final DirectoryAccess directoryAccess;

    /** The observer each temporary file is shown to once it holds the content, or {@code null} for none. */
    private final TemporaryFileObserver temporaryFileObserver;

    /**
     * The observer shown the directory each missing directory is created in, just before it is created, or
     * {@code null} for none.
     */
    private final DirectoryCreationObserver directoryCreationObserver;

    /**
     * Creates a writer that walks directory-relative where the file system allows it, with no temporary-file
     * observer; every instance shares the class's per-destination locks, so one instance or several serve callers
     * alike.
     */
    public FileOutboundWriter() {
        this.directoryAccess = DirectoryAccess.AUTOMATIC;
        this.temporaryFileObserver = null;
        this.directoryCreationObserver = null;
    }

    /**
     * Creates a writer that reaches directories as {@code directoryAccess} says, with no temporary-file observer; it
     * shares the class's per-destination locks like every instance. Package-private for the unit test only, which
     * forces the path-name walk with it.
     *
     * @param directoryAccess how the writer reaches the directories it walks
     */
    FileOutboundWriter(DirectoryAccess directoryAccess) {
        this.directoryAccess = Objects.requireNonNull(directoryAccess, "directoryAccess");
        this.temporaryFileObserver = null;
        this.directoryCreationObserver = null;
    }

    /**
     * Creates a writer that reaches directories as {@code directoryAccess} says and shows
     * {@code temporaryFileObserver} the temporary file of each whole-file write once it holds the content, before it
     * takes the replaced file's POSIX permissions and is renamed into place; it shares the class's per-destination
     * locks like every instance. Package-private for the unit test only.
     *
     * @param directoryAccess       how the writer reaches the directories it walks
     * @param temporaryFileObserver the observer of each temporary file
     */
    FileOutboundWriter(DirectoryAccess directoryAccess, TemporaryFileObserver temporaryFileObserver) {
        this.directoryAccess = Objects.requireNonNull(directoryAccess, "directoryAccess");
        this.temporaryFileObserver = Objects.requireNonNull(temporaryFileObserver, "temporaryFileObserver");
        this.directoryCreationObserver = null;
    }

    /**
     * Creates a writer that reaches directories as {@code directoryAccess} says, shows {@code temporaryFileObserver}
     * the temporary file of each whole-file write once it holds the content, before it takes the replaced file's
     * POSIX permissions and is renamed into place, and shows {@code directoryCreationObserver} the directory each
     * missing directory on the walk is created in, just before it is created; it shares the class's
     * per-destination locks like every instance. Package-private for the unit test only.
     *
     * @param directoryAccess           how the writer reaches the directories it walks
     * @param temporaryFileObserver     the observer of each temporary file
     * @param directoryCreationObserver the observer of each directory creation
     */
    FileOutboundWriter(DirectoryAccess directoryAccess, TemporaryFileObserver temporaryFileObserver,
            DirectoryCreationObserver directoryCreationObserver) {
        this.directoryAccess = Objects.requireNonNull(directoryAccess, "directoryAccess");
        this.temporaryFileObserver = Objects.requireNonNull(temporaryFileObserver, "temporaryFileObserver");
        this.directoryCreationObserver =
                Objects.requireNonNull(directoryCreationObserver, "directoryCreationObserver");
    }

    /**
     * Writes {@code content} to {@code dir/name}, creating {@code dir} and then the directories between it and the
     * file when missing, and replacing any existing file as a whole. {@code name} is joined to {@code dir} as Mule
     * joined {@code address + "/" + filename}: its leading separators are dropped and the rest is resolved against
     * {@code dir}, every {@code ..} segment kept. The name is walked from a handle on {@code dir}, directory-relative
     * where the file system offers a {@link SecureDirectoryStream}, and a symbolic link met on the way is followed only
     * while it stays inside {@code dir}; one that leads out of it fails the write and nothing outside {@code dir}
     * changes. The content goes to a temporary file beside the destination, owner-only while it receives the content,
     * which then gets the replaced file's POSIX permissions and is renamed over it, under a lock that serialises
     * writers of the same destination within the JVM. The rename is atomic where the file system supports an atomic
     * rename; on one that does not, it is a replacing move, which is not atomic, so readers can see the destination
     * missing or incomplete while it runs. A new file gets the permissions an ordinary create gives it. A confined
     * symbolic link at {@code dir/name} is followed and the file it links to is the one replaced or created. An
     * existing file that is neither a regular file nor a directory is written in place, and so is an existing file in
     * a directory that refuses the process the temporary file or its rename, as a sticky directory refuses the rename
     * over a file another user owns, and a regular file that a failed replacing move leaves at the destination;
     * readers can then see it part-written. A writer created with a temporary-file observer shows it the temporary
     * file once it holds the content, one created with a directory-creation observer shows it the directory each
     * missing directory is created in, just before it is created, and an {@link IOException} of an observer ends the
     * write as a failed write does.
     *
     * @param dir     the target directory, created with its missing parents
     * @param name    the resolved output file name, a leading separator not taking it out of {@code dir}
     * @param content the bytes to write, exactly as given
     * @return the written file, {@code dir} resolved with {@code name} less its leading separators
     * @throws NullPointerException when {@code dir}, {@code name} or {@code content} is {@code null}, naming it
     * @throws IOException          with the message {@code Streaming failed. Could not get output stream} whenever
     *                              the write fails, its cause the failure itself: an {@link AccessDeniedException}
     *                              when the existing file is one the process may not write, which is left as it
     *                              was, when the file is new and its directory does not let the process create
     *                              it, or naming a directory on the walk that the process may search but not read,
     *                              which cannot be held open; a {@link FileSystemException} with the reason
     *                              {@value #LINK_NOT_CONFINED} naming a symbolic link on the way that is not
     *                              confined to {@code dir}; an {@link InvalidPathException} when {@code name} is no
     *                              valid path; otherwise the {@link IOException} of the directory that cannot be
     *                              created or the file that cannot be written. The existing file is left as it was,
     *                              unless it was being written in place or a failed replacing move removed it
     */
    public Path write(Path dir, String name, byte[] content) throws IOException {
        Objects.requireNonNull(dir, "dir");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(content, "content");
        try {
            Files.createDirectories(dir);
            Path relative = relativeName(dir, name);
            Path target = dir.resolve(relative);
            try (Walk walk = new Walk(dir, target, openDirectory(dir, directoryAccess), directoryCreationObserver)) {
                Destination destination = walk.toFile(relative);
                LockKey key = lockKey(destination.directory(), destination.name());
                TargetLock entry = LOCKS.compute(key, (lockKey, existing) -> {
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
                    LOCKS.computeIfPresent(key, (lockKey, held) -> --held.holders == 0 ? null : held);
                }
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
     * Returns {@code name} as the path it names relative to {@code dir}, the way Mule's file connector joined its
     * endpoint address and file name, {@code address + "/" + filename}, which {@link java.io.File} normalised by
     * collapsing the doubled separator: the separators {@code name} starts with, {@code /} and the file system's own,
     * are dropped and the rest is the relative path that {@code dir} is resolved with. A name that is an absolute path
     * therefore names a file under {@code dir}, and every other character of the name, {@code ..} segments included,
     * is kept as given.
     *
     * @param dir  the target directory
     * @param name the resolved output file name
     * @return {@code name} less its leading separators, as a path of the file system of {@code dir}
     * @throws InvalidPathException when the rest of {@code name} is not a valid path, for example when it holds a NUL
     *                              character
     */
    private static Path relativeName(Path dir, String name) {
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
        return dir.getFileSystem().getPath(name.substring(start));
    }

    /**
     * Opens a handle on the existing directory {@code dir}, following a symbolic link that {@code dir} itself is or
     * passes through, since the configured directory is trusted as given. With {@link DirectoryAccess#AUTOMATIC}
     * {@code dir} is opened with {@link Files#newDirectoryStream(Path)}, which needs read permission on it: when that
     * returns a {@link SecureDirectoryStream}, the handle holds it open and works directory-relative; when it returns
     * a stream that is not secure, as the JDK 17 does on macOS and Windows, the handle works on path names; and when
     * the process may search {@code dir} but not read it, the open's {@link AccessDeniedException} naming {@code dir}
     * is thrown, since such a directory cannot be held open. With {@link DirectoryAccess#PATH_NAMES} the handle works
     * on path names. Package-private for the unit test, which opens the handle
     * {@link #createTemporaryFile(DirectoryHandle, Path, Path)} takes.
     *
     * @param dir    the directory
     * @param access how the handle reaches the directory's entries
     * @return a handle on {@code dir}, to be closed by the caller
     * @throws AccessDeniedException with {@link DirectoryAccess#AUTOMATIC}, naming {@code dir}, when the process may
     *                               not read it
     * @throws IOException           when {@code dir} cannot be opened or its attributes cannot be read
     */
    static DirectoryHandle openDirectory(Path dir, DirectoryAccess access) throws IOException {
        if (access == DirectoryAccess.AUTOMATIC) {
            DirectoryStream<Path> stream = Files.newDirectoryStream(dir);
            if (stream instanceof SecureDirectoryStream<Path> secure) {
                return new SecureHandle(secure, dir);
            }
            stream.close();
        }
        return new PathHandle(dir, Files.readAttributes(dir, BasicFileAttributes.class).fileKey());
    }

    /**
     * Returns the key of the lock that serialises writers of the entry {@code name} of {@code directory}: the
     * directory's file key or, on a file system without file keys, its real path, with the entry's name.
     *
     * @param directory the directory holding the destination
     * @param name      the destination's name in it
     * @return the lock key
     * @throws IOException when the directory's identity cannot be read
     */
    private static LockKey lockKey(DirectoryHandle directory, Path name) throws IOException {
        Object identity = directory.fileKey();
        if (identity == null) {
            identity = directory.path().toRealPath();
        }
        return new LockKey(identity, name.toString());
    }

    /**
     * Writes {@code content} to the destination the walk reached, acting on it only through the handle of its
     * directory and never through a symbolic link; the caller holds the destination's lock. The entry is read again
     * under the lock: a directory fails; a regular file, a missing entry and a symbolic link swapped in since the
     * walk, which is never followed, are replaced as a whole: the content is written to a new hidden file in the
     * destination's directory, created by {@link #createTemporaryFile(DirectoryHandle, Path, Path)} before any content
     * reaches it, shown to {@code observer} once it holds the whole content and is closed, confirmed to still be the
     * file created, then given the existing file's POSIX permissions and renamed over the destination. When the
     * directory refuses to create the temporary file with an {@link AccessDeniedException}, before any content is
     * written, the destination is written in place by {@link #writeInPlace(DirectoryHandle, Path, byte[])} instead,
     * as Mule wrote it: an existing file the process may write is overwritten, keeping its inode, owner and mode but
     * not atomically for its readers, and a new file fails as its in-place create fails. When the rename fails with an
     * {@link IOException}, as a sticky directory refuses it over a file another user owns, the temporary file is
     * removed and a destination that is still an existing regular file is written in place the same way: the
     * in-place write's outcome is the one reported, a failure of it carrying the refused rename as a suppressed
     * exception, while the refused rename of a new file is thrown unchanged. On any other failure, a failure of
     * {@code observer} included, the temporary file is removed and the destination is left as it was. An existing
     * file that is neither a regular file, a directory nor a symbolic link, such as a named pipe or a device, is
     * written in place the same way.
     *
     * @param destination the entry to replace or create, in the directory the walk reached
     * @param content     the bytes the file must hold
     * @param observer    the observer of the temporary file, or {@code null} for none
     * @throws AccessDeniedException when the destination is a regular file the process may not write, or a new file
     *                               its directory does not let the process create; no file is created
     * @throws IOException           when the destination is a directory, the file cannot be written, the temporary
     *                               file was replaced, a new file cannot be renamed into place, or {@code observer}
     *                               throws it
     */
    private static void replace(Destination destination, byte[] content, TemporaryFileObserver observer)
            throws IOException {
        DirectoryHandle directory = destination.directory();
        Path name = destination.name();
        BasicFileAttributes existing = attributesOrNull(directory, name);
        if (existing != null && existing.isDirectory()) {
            throw new FileSystemException(destination.path().toString(), null, IS_A_DIRECTORY);
        }
        if (existing != null && existing.isRegularFile()) {
            if (!Files.isWritable(destination.path())) {
                throw new AccessDeniedException(destination.path().toString());
            }
        } else if (existing != null && existing.isOther()) {
            writeInPlace(directory, name, content);
            return;
        }
        Path temp = name.getFileSystem().getPath("." + UUID.randomUUID() + ".tmp");
        SeekableByteChannel channel;
        try {
            channel = createTemporaryFile(directory, temp, name);
        } catch (AccessDeniedException directoryRefusesNewEntry) {
            writeInPlace(directory, name, content);
            return;
        } catch (IOException | RuntimeException | Error failure) {
            discard(directory, temp, failure);
            throw failure;
        }
        try {
            Object identity;
            try (channel) {
                identity = directory.attributes(temp).fileKey();
                writeFully(channel, content);
            }
            if (observer != null) {
                observer.contentWritten(directory.path().resolve(temp));
            }
            BasicFileAttributes written = directory.attributes(temp);
            if (!written.isRegularFile() || !Objects.equals(written.fileKey(), identity)) {
                throw new FileSystemException(directory.path().resolve(temp).toString(), null,
                        TEMPORARY_FILE_REPLACED);
            }
            copyPosixPermissions(directory, name, temp);
        } catch (IOException | RuntimeException | Error failure) {
            discard(directory, temp, failure);
            throw failure;
        }
        try {
            directory.move(temp, name);
        } catch (IOException refused) {
            discard(directory, temp, refused);
            if (!isRegularFile(directory, name)) {
                throw refused;
            }
            try {
                writeInPlace(directory, name, content);
            } catch (IOException | RuntimeException | Error inPlaceFailure) {
                inPlaceFailure.addSuppressed(refused);
                throw inPlaceFailure;
            }
        } catch (RuntimeException | Error failure) {
            discard(directory, temp, failure);
            throw failure;
        }
    }

    /**
     * Writes {@code content} to the entry {@code name} of {@code directory} in place, as Mule's file connector wrote
     * every file: the file is opened to be created, truncated and written, never through a symbolic link, so an
     * existing one keeps its inode, owner and mode, and a reader can see it empty or partly written until the write
     * completes. The caller holds the destination's lock.
     *
     * @param directory the directory holding the file
     * @param name      the file to overwrite or create
     * @param content   the bytes the file must hold
     * @throws AccessDeniedException when the process may not write the file, or may not create it in its directory
     * @throws IOException           when the file cannot be opened, for example because it is a symbolic link, or
     *                               cannot be written
     */
    private static void writeInPlace(DirectoryHandle directory, Path name, byte[] content) throws IOException {
        try (SeekableByteChannel channel = directory.newByteChannel(name, IN_PLACE_OPTIONS)) {
            writeFully(channel, content);
        }
    }

    /**
     * Writes the whole of {@code content} to {@code channel} from its current position.
     *
     * @param channel the channel to write to
     * @param content the bytes to write
     * @throws IOException when the channel cannot be written
     */
    private static void writeFully(SeekableByteChannel channel, byte[] content) throws IOException {
        ByteBuffer remaining = ByteBuffer.wrap(content);
        while (remaining.hasRemaining()) {
            channel.write(remaining);
        }
    }

    /**
     * Creates the empty temporary file {@code temp} in {@code directory}, never over an existing entry or through a
     * symbolic link, that will replace the entry {@code destination}, and opens it for writing. When
     * {@code destination} is an existing regular file on a file store with POSIX permissions, {@code temp} is created
     * readable and writable by its owner only, by the same call that creates it, so no other user can read the content
     * written to it next, whatever the replaced file's mode; it gets that file's permissions only once the content is
     * complete. Otherwise {@code temp} is created as an ordinary create creates a file, so a new file gets the
     * permissions the process's umask gives it. Package-private for the unit test only.
     *
     * @param directory   the directory to create {@code temp} in
     * @param temp        the name of the temporary file to create, which must not exist
     * @param destination the name of the entry {@code temp} will replace, which may not exist yet
     * @return a channel writing to the empty {@code temp} from its start
     * @throws IOException when {@code temp} exists already or cannot be created
     */
    static SeekableByteChannel createTemporaryFile(DirectoryHandle directory, Path temp, Path destination)
            throws IOException {
        if (hasPosixPermissions(directory, destination)) {
            return directory.newByteChannel(temp, TEMPORARY_FILE_OPTIONS, OWNER_ONLY);
        }
        return directory.newByteChannel(temp, TEMPORARY_FILE_OPTIONS);
    }

    /**
     * Gives {@code temp} the POSIX permissions of an existing regular {@code destination}, so the replaced file keeps
     * its mode as an in-place overwrite did. The destination's type and permissions come from one read that does not
     * follow a symbolic link, and the permissions are set without following one. Nothing is copied for a new file, a
     * destination that is no regular file, or on a file store without POSIX permissions; a destination another
     * process removed while the content was written leaves {@code temp} owner-only.
     *
     * @param directory   the directory holding both entries
     * @param destination the name of the file being replaced, which may not exist yet
     * @param temp        the name of the file that will replace it
     * @throws IOException when the permissions cannot be read or set, for example on a symbolic link
     */
    private static void copyPosixPermissions(DirectoryHandle directory, Path destination, Path temp)
            throws IOException {
        if (!Files.getFileStore(directory.path()).supportsFileAttributeView(PosixFileAttributeView.class)) {
            return;
        }
        PosixFileAttributes replaced;
        try {
            replaced = directory.posixAttributes(destination);
        } catch (NoSuchFileException removed) {
            return;
        }
        if (replaced.isRegularFile()) {
            directory.setPermissions(temp, replaced.permissions());
        }
    }

    /**
     * Tells whether {@code destination} is an existing regular file whose POSIX permissions its replacement takes.
     *
     * @param directory   the directory holding the file
     * @param destination the name of the file being replaced, which may not exist yet
     * @return {@code true} for an existing regular file, not a symbolic link, on a file store with POSIX permissions
     * @throws IOException when the entry or the file store of {@code directory} cannot be read
     */
    private static boolean hasPosixPermissions(DirectoryHandle directory, Path destination) throws IOException {
        BasicFileAttributes attributes = attributesOrNull(directory, destination);
        return attributes != null && attributes.isRegularFile()
                && Files.getFileStore(directory.path()).supportsFileAttributeView(PosixFileAttributeView.class);
    }

    /**
     * Tells whether the entry {@code name} of {@code directory} is a regular file, without following a symbolic link.
     * An entry that cannot be read is no regular file a write could complete in place.
     *
     * @param directory the directory holding the entry
     * @param name      the entry's name
     * @return {@code true} for an existing regular file
     */
    private static boolean isRegularFile(DirectoryHandle directory, Path name) {
        try {
            return directory.attributes(name).isRegularFile();
        } catch (IOException unreadable) {
            return false;
        }
    }

    /**
     * Reads the attributes of the entry {@code name} of {@code directory} without following a symbolic link.
     *
     * @param directory the directory holding the entry
     * @param name      the entry's name
     * @return its attributes, or {@code null} when it does not exist
     * @throws IOException when the entry exists but cannot be read
     */
    private static BasicFileAttributes attributesOrNull(DirectoryHandle directory, Path name) throws IOException {
        try {
            return directory.attributes(name);
        } catch (NoSuchFileException missing) {
            return null;
        }
    }

    /**
     * Removes the temporary file of a failed write from its directory, without following a symbolic link,
     * recording a failure to do so on the write's own failure so that the original cause is the one the caller
     * receives.
     *
     * @param directory the directory holding the temporary file
     * @param temp      the temporary file's name, which may not have been created
     * @param failure   the failure that ended the write
     */
    private static void discard(DirectoryHandle directory, Path temp, Throwable failure) {
        try {
            directory.deleteIfExists(temp);
        } catch (IOException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    /**
     * Returns {@code failure}, an exception of a directory-relative operation, which names the entry relative to its
     * directory, as the same exception naming {@code file} and {@code otherFile} by their full paths, as the
     * operation on path names names them, with {@code failure} as its cause. Exceptions of other types are returned
     * unchanged.
     *
     * @param failure   the exception of the directory-relative operation
     * @param file      the full path of the entry the operation acted on
     * @param otherFile the full path of the second entry of a rename, or {@code null} for none
     * @return the exception naming the full paths
     */
    private static IOException withFullPaths(IOException failure, Path file, Path otherFile) {
        if (!(failure instanceof FileSystemException named)) {
            return failure;
        }
        String path = file.toString();
        String other = otherFile == null ? named.getOtherFile() : otherFile.toString();
        FileSystemException renamed;
        if (named instanceof AccessDeniedException) {
            renamed = new AccessDeniedException(path, other, named.getReason());
        } else if (named instanceof FileAlreadyExistsException) {
            renamed = new FileAlreadyExistsException(path, other, named.getReason());
        } else if (named instanceof NoSuchFileException) {
            renamed = new NoSuchFileException(path, other, named.getReason());
        } else if (named instanceof NotDirectoryException) {
            renamed = new NotDirectoryException(path);
        } else if (named.getClass() == FileSystemException.class) {
            renamed = new FileSystemException(path, other, named.getReason());
        } else {
            return failure;
        }
        renamed.initCause(failure);
        return renamed;
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
     * Observes the walk of a write just before it creates a missing directory the name leads through. Package-private
     * for the unit test only.
     */
    interface DirectoryCreationObserver {

        /**
         * Called in the writing thread, before the destination's lock is taken, just before a missing directory is
         * created in the directory the walk holds. A failure it throws ends the write like any write failure: the
         * missing directory is not created, an {@link IOException} is the cause of the writer's
         * {@code Streaming failed. Could not get output stream} and any other failure reaches the caller unchanged.
         *
         * @param parent the path the walk reached the directory by in which the missing directory is created
         * @throws IOException to end the write with this failure
         */
        void creatingDirectory(Path parent) throws IOException;
    }

    /** How a writer reaches the directories it walks. Package-private for the unit test only. */
    enum DirectoryAccess {

        /**
         * Directory-relative, through a {@link SecureDirectoryStream}, where {@link Files#newDirectoryStream(Path)}
         * returns one for the target directory, every directory on the walk held open and a directory the process
         * may search but not read, which cannot be held open, failing the write; on path names only where it returns
         * a stream that is not secure, as the JDK 17 does on macOS and Windows: the access of every production
         * writer.
         */
        AUTOMATIC,

        /**
         * On path names with {@link LinkOption#NOFOLLOW_LINKS} and the same checks, as on a file system without
         * directory-relative operations; the unit test forces it to cover that implementation on one with them.
         */
        PATH_NAMES
    }

    /**
     * An open directory the writer acts in. Every entry is named by a single path element relative to it, and no
     * operation follows a symbolic link that the entry is. Package-private for the unit test only, which opens one
     * with {@link #openDirectory(Path, DirectoryAccess)} for {@link #createTemporaryFile(DirectoryHandle, Path, Path)}.
     */
    interface DirectoryHandle extends Closeable {

        /**
         * Returns the path the walk reached this directory by. It serves the operations the JDK offers on path names
         * only, and names entries in exceptions.
         *
         * @return the directory's path
         */
        Path path();

        /**
         * Returns the identity of this directory, read from the open directory where it is held open and otherwise
         * when the walk entered it.
         *
         * @return its file key, or {@code null} on a file system without file keys
         * @throws IOException when the directory's attributes cannot be read
         */
        Object fileKey() throws IOException;

        /**
         * Reads the attributes of an entry without following a symbolic link.
         *
         * @param name the entry's name
         * @return its attributes
         * @throws NoSuchFileException when the entry does not exist
         * @throws IOException         when the attributes cannot be read
         */
        BasicFileAttributes attributes(Path name) throws IOException;

        /**
         * Opens a directory entry, failing rather than following when it is a symbolic link.
         *
         * @param name the entry's name, {@code ..} for the parent directory
         * @return a handle on the entered directory, to be closed by the caller
         * @throws IOException when the entry is no directory, is a symbolic link, or cannot be opened
         */
        DirectoryHandle openDirectory(Path name) throws IOException;

        /**
         * Creates a missing directory entry in this directory. A directory another writer created under the name
         * meanwhile is accepted; the entry is then confirmed to be a directory, without following a symbolic link,
         * before the caller opens it.
         *
         * @param name the missing directory's name
         * @throws FileAlreadyExistsException when an entry that is no directory took the name meanwhile
         * @throws FileSystemException        when the directory cannot be created where this handle requires it to be
         *                                    created, with the reason {@value #NOT_CREATED_RELATIVE} or
         *                                    {@value #DIRECTORY_REPLACED}
         * @throws IOException                when the directory cannot be created
         */
        void createDirectory(Path name) throws IOException;

        /**
         * Opens or creates a file entry.
         *
         * @param name       the entry's name
         * @param options    how to open it, {@link LinkOption#NOFOLLOW_LINKS} among them
         * @param attributes the attributes a created file gets
         * @return a channel to the file
         * @throws IOException when the file cannot be opened or created
         */
        SeekableByteChannel newByteChannel(Path name, Set<? extends OpenOption> options,
                FileAttribute<?>... attributes) throws IOException;

        /**
         * Reads the POSIX attributes of an entry, its type and permissions among them, without following a symbolic
         * link.
         *
         * @param name the entry's name
         * @return its attributes
         * @throws NoSuchFileException when the entry does not exist
         * @throws IOException         when the attributes cannot be read
         */
        PosixFileAttributes posixAttributes(Path name) throws IOException;

        /**
         * Sets the POSIX permissions of an entry, failing rather than following when it is a symbolic link.
         *
         * @param name        the entry's name
         * @param permissions the permissions it gets
         * @throws IOException when they cannot be set, for example on a symbolic link
         */
        void setPermissions(Path name, Set<PosixFilePermission> permissions) throws IOException;

        /**
         * Renames one entry over another in this directory, replacing a symbolic link at {@code target} rather than
         * following it.
         *
         * @param source the entry to rename
         * @param target the name it gets, replacing any entry of that name
         * @throws IOException when the rename fails
         */
        void move(Path source, Path target) throws IOException;

        /**
         * Removes a file entry, a symbolic link itself and never the entry it leads to.
         *
         * @param name the entry's name
         * @return {@code true} when the entry existed and was removed, {@code false} when it did not exist
         * @throws IOException when the entry exists but cannot be removed
         */
        boolean deleteIfExists(Path name) throws IOException;
    }

    /**
     * A directory held open as a {@link SecureDirectoryStream}: every entry, a missing directory included, is checked,
     * created and acted on relative to the open directory, so a rename of the directory or a link swapped in for one
     * above it does not redirect what is done in it. Exceptions name the entry by its full path. A directory entry
     * the process may search but not read cannot be held open, so {@link #openDirectory(Path)} refuses it.
     */
    private static final class SecureHandle implements DirectoryHandle {

        /** The open directory. */
        private final SecureDirectoryStream<Path> stream;

        /** The path the walk reached the directory by. */
        private final Path path;

        /**
         * Wraps an open directory.
         *
         * @param stream the open directory, closed with the handle
         * @param path   the path the walk reached it by
         */
        SecureHandle(SecureDirectoryStream<Path> stream, Path path) {
            this.stream = stream;
            this.path = path;
        }

        @Override
        public Path path() {
            return path;
        }

        @Override
        public Object fileKey() throws IOException {
            try {
                return stream.getFileAttributeView(BasicFileAttributeView.class).readAttributes().fileKey();
            } catch (IOException failure) {
                throw withFullPaths(failure, path, null);
            }
        }

        @Override
        public BasicFileAttributes attributes(Path name) throws IOException {
            try {
                return stream.getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                        .readAttributes();
            } catch (IOException failure) {
                throw withFullPaths(failure, path.resolve(name), null);
            }
        }

        /**
         * Opens a directory entry relative to this open directory, failing rather than following when it is a
         * symbolic link. Holding a directory open needs read permission on it, so a directory the process may search
         * but not read refuses the open with an {@link AccessDeniedException}, which is thrown like every other
         * failure of the open, naming the entry by its full path.
         *
         * @param name the entry's name, {@code ..} for the parent directory
         * @return a handle holding the entered directory open, to be closed by the caller
         * @throws AccessDeniedException when the process may not read the entry
         * @throws IOException           when the entry is no directory, is a symbolic link, or cannot be opened
         */
        @Override
        public DirectoryHandle openDirectory(Path name) throws IOException {
            Path entered = path.resolve(name);
            try {
                return new SecureHandle(stream.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS), entered);
            } catch (IOException failure) {
                throw withFullPaths(failure, entered, null);
            }
        }

        /**
         * Creates a missing directory entry relative to this open directory. The JDK offers no directory-relative
         * directory creation, so the directory is created through the entry {@code /proc/self/fd/<n>} that names
         * this open directory, which the kernel resolves to the open directory itself: no rename of it and no link
         * swapped in for it or for a directory above it can redirect the creation, and its path is never used. Every
         * writer's directories held open are kept from being closed from the choice of the entry until the directory
         * is created, so an entry naming one of them cannot meanwhile come to name another file; the JDK does not
         * expose which descriptor is this handle's own, so the entry may also be one that other code of the process
         * holds on the same directory, which that code could close, and the process reuse for another file, in that
         * instant. The created entry is then confirmed relative to this directory, without following a symbolic link,
         * to be a directory, so a creation that went elsewhere fails the write.
         *
         * @param name the missing directory's name
         * @throws FileSystemException        naming the directory to create, with the reason
         *                                    {@value #NOT_CREATED_RELATIVE}, when no entry of {@code /proc/self/fd}
         *                                    names this open directory or the listing cannot be read; the directory
         *                                    is not created
         * @throws FileAlreadyExistsException naming it, when an entry that is no directory took the name meanwhile
         * @throws IOException                naming it, when the directory cannot be created or its entry read
         */
        @Override
        public void createDirectory(Path name) throws IOException {
            Path created = path.resolve(name);
            Object identity = fileKey();
            FileAlreadyExistsException createdMeanwhile = null;
            Lock shared = HELD_DIRECTORIES.readLock();
            shared.lock();
            try {
                Path descriptor = descriptorOf(OPEN_DESCRIPTORS, identity, created);
                try {
                    Files.createDirectory(descriptor.resolve(name.toString()));
                } catch (FileAlreadyExistsException exists) {
                    createdMeanwhile = exists;
                } catch (IOException failure) {
                    throw withFullPaths(failure, created, null);
                }
            } finally {
                shared.unlock();
            }
            if (!attributes(name).isDirectory()) {
                throw createdMeanwhile == null
                        ? new FileAlreadyExistsException(created.toString())
                        : withFullPaths(createdMeanwhile, created, null);
            }
        }

        @Override
        public SeekableByteChannel newByteChannel(Path name, Set<? extends OpenOption> options,
                FileAttribute<?>... attributes) throws IOException {
            try {
                return stream.newByteChannel(name, options, attributes);
            } catch (IOException failure) {
                throw withFullPaths(failure, path.resolve(name), null);
            }
        }

        @Override
        public PosixFileAttributes posixAttributes(Path name) throws IOException {
            try {
                return posix(stream.getFileAttributeView(name, PosixFileAttributeView.class,
                        LinkOption.NOFOLLOW_LINKS)).readAttributes();
            } catch (IOException failure) {
                throw withFullPaths(failure, path.resolve(name), null);
            }
        }

        @Override
        public void setPermissions(Path name, Set<PosixFilePermission> permissions) throws IOException {
            try {
                posix(stream.getFileAttributeView(name, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS))
                        .setPermissions(permissions);
            } catch (IOException failure) {
                throw withFullPaths(failure, path.resolve(name), null);
            }
        }

        @Override
        public void move(Path source, Path target) throws IOException {
            try {
                stream.move(source, stream, target);
            } catch (IOException failure) {
                throw withFullPaths(failure, path.resolve(source), path.resolve(target));
            }
        }

        @Override
        public boolean deleteIfExists(Path name) throws IOException {
            try {
                stream.deleteFile(name);
                return true;
            } catch (NoSuchFileException missing) {
                return false;
            } catch (IOException failure) {
                throw withFullPaths(failure, path.resolve(name), null);
            }
        }

        /**
         * Closes the open directory, once no missing directory is being created through an open file descriptor, so
         * the descriptors of this directory cannot be reused for another file while one is chosen to name it.
         *
         * @throws IOException when the directory cannot be closed
         */
        @Override
        public void close() throws IOException {
            Lock exclusive = HELD_DIRECTORIES.writeLock();
            exclusive.lock();
            try {
                stream.close();
            } finally {
                exclusive.unlock();
            }
        }
    }

    /**
     * Returns the entry of {@code descriptors}, the listing of this process's open file descriptors, that names the
     * open directory whose file key is {@code identity}: the first entry that, followed, is a directory with that file
     * key. An entry whose descriptor was closed meanwhile, or that cannot be followed, is passed over. A missing
     * directory is never created by its own path instead, so every failure to find the entry fails the creation.
     * Package-private for the unit test only, which covers those failures with listings of its own.
     *
     * @param descriptors the listing of the open file descriptors, {@link #OPEN_DESCRIPTORS} in production
     * @param identity    the file key of the open directory, {@code null} on a file system without file keys
     * @param created     the full path of the directory to be created, which the failure names
     * @return the entry naming the open directory
     * @throws FileSystemException naming {@code created}, with the reason {@value #NOT_CREATED_RELATIVE}, when no
     *                             entry names the open directory, {@code identity} is {@code null}, or the listing
     *                             cannot be read, the failure reading it then the cause
     */
    static Path descriptorOf(Path descriptors, Object identity, Path created) throws FileSystemException {
        IOException unreadable = null;
        if (identity != null) {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(descriptors)) {
                for (Path entry : entries) {
                    BasicFileAttributes attributes;
                    try {
                        attributes = Files.readAttributes(entry, BasicFileAttributes.class);
                    } catch (IOException closedMeanwhile) {
                        continue;
                    }
                    if (attributes.isDirectory() && identity.equals(attributes.fileKey())) {
                        return entry;
                    }
                }
            } catch (DirectoryIteratorException iteration) {
                unreadable = iteration.getCause();
            } catch (IOException failure) {
                unreadable = failure;
            }
        }
        FileSystemException refused = new FileSystemException(created.toString(), null, NOT_CREATED_RELATIVE);
        if (unreadable != null) {
            refused.initCause(unreadable);
        }
        throw refused;
    }

    /**
     * A directory reached by its path, used only where {@link Files#newDirectoryStream(Path)} returns no
     * {@link SecureDirectoryStream}, as the JDK 17 does on macOS and Windows, and with
     * {@link DirectoryAccess#PATH_NAMES}: every entry is checked and acted on by path name with
     * {@link LinkOption#NOFOLLOW_LINKS}, so a symbolic link that the entry itself is is never followed, while a link
     * swapped in for a directory on the path, between a check and the operation it guards, is.
     */
    private static final class PathHandle implements DirectoryHandle {

        /** The directory's path. */
        private final Path path;

        /** The directory's identity when the walk entered it, or {@code null} on a file system without file keys. */
        private final Object fileKey;

        /** Whether the handle is still open; once closed it refuses every operation, as a closed stream does. */
        private boolean open = true;

        /**
         * Creates the handle of a directory the walk entered.
         *
         * @param path    the directory's path
         * @param fileKey its identity when entered
         */
        PathHandle(Path path, Object fileKey) {
            this.path = path;
            this.fileKey = fileKey;
        }

        @Override
        public Path path() {
            return path;
        }

        @Override
        public Object fileKey() {
            return fileKey;
        }

        @Override
        public BasicFileAttributes attributes(Path name) throws IOException {
            return Files.readAttributes(entry(name), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        }

        @Override
        public DirectoryHandle openDirectory(Path name) throws IOException {
            Path entered = entry(name);
            BasicFileAttributes attributes =
                    Files.readAttributes(entered, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory()) {
                throw new NotDirectoryException(entered.toString());
            }
            return new PathHandle(entered, attributes.fileKey());
        }

        /**
         * Creates a missing directory entry by its path name, once the path of this directory is confirmed to still
         * lead to the directory the walk entered; a link swapped in for a directory on the path after that check can
         * still redirect the creation.
         *
         * @param name the missing directory's name
         * @throws FileSystemException        naming this directory, with the reason {@value #DIRECTORY_REPLACED},
         *                                    when its path no longer leads to it
         * @throws FileAlreadyExistsException when an entry that is no directory took the name meanwhile
         * @throws IOException                when the directory cannot be created
         */
        @Override
        public void createDirectory(Path name) throws IOException {
            Path created = entry(name);
            Object found = Files.readAttributes(path, BasicFileAttributes.class).fileKey();
            if (!Objects.equals(found, fileKey)) {
                throw new FileSystemException(path.toString(), null, DIRECTORY_REPLACED);
            }
            try {
                Files.createDirectory(created);
            } catch (FileAlreadyExistsException createdMeanwhile) {
                if (!attributes(name).isDirectory()) {
                    throw createdMeanwhile;
                }
            }
        }

        @Override
        public SeekableByteChannel newByteChannel(Path name, Set<? extends OpenOption> options,
                FileAttribute<?>... attributes) throws IOException {
            return Files.newByteChannel(entry(name), options, attributes);
        }

        @Override
        public PosixFileAttributes posixAttributes(Path name) throws IOException {
            return posix(Files.getFileAttributeView(entry(name), PosixFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS)).readAttributes();
        }

        @Override
        public void setPermissions(Path name, Set<PosixFilePermission> permissions) throws IOException {
            posix(Files.getFileAttributeView(entry(name), PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS))
                    .setPermissions(permissions);
        }

        @Override
        public void move(Path source, Path target) throws IOException {
            Path from = entry(source);
            Path to = entry(target);
            try {
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        @Override
        public boolean deleteIfExists(Path name) throws IOException {
            return Files.deleteIfExists(entry(name));
        }

        @Override
        public void close() {
            open = false;
        }

        /**
         * Returns the path of an entry of the open handle.
         *
         * @param name the entry's name
         * @return the directory's path resolved with it
         * @throws ClosedDirectoryStreamException when the handle is closed
         */
        private Path entry(Path name) {
            if (!open) {
                throw new ClosedDirectoryStreamException();
            }
            return path.resolve(name);
        }
    }

    /**
     * Returns a POSIX attribute view the file store supports, as {@link Files#getPosixFilePermissions} requires one.
     *
     * @param view the view, {@code null} when the file system has none
     * @return the view
     * @throws UnsupportedOperationException when the file system has no POSIX attribute view
     */
    private static PosixFileAttributeView posix(PosixFileAttributeView view) {
        if (view == null) {
            throw new UnsupportedOperationException("POSIX file attributes are not supported");
        }
        return view;
    }

    /**
     * The walk of one write from the target directory to the entry its name leads to, holding a handle on every
     * directory entered. While the walk is inside the target directory, a {@code ..} returns to the handle it came
     * from, and a symbolic link is followed only within the directory: its elements continue from the link's own
     * directory, or for absolute text that starts with the directory, from the directory itself. Once the name's own
     * {@code ..} segments take the walk above the target directory, it is outside for good: each {@code ..} then
     * opens the physical parent directory and no symbolic link is followed.
     */
    private static final class Walk implements Closeable {

        /** The target directory as given, which the walk starts from. */
        private final Path dir;

        /** The file the caller named, which the failure of too many links names. */
        private final Path target;

        /** The observer of each directory creation, or {@code null} for none. */
        private final DirectoryCreationObserver observer;

        /** The handles of the directories entered, the current one first and the target directory's last. */
        private final Deque<DirectoryHandle> entered = new ArrayDeque<>();

        /** The prefixes of absolute link text that lead into the target directory, read on first use. */
        private List<Path> rootPrefixes;

        /** Whether the name's own {@code ..} segments have taken the walk above the target directory. */
        private boolean outside;

        /** The symbolic links followed so far. */
        private int hops;

        /**
         * Starts a walk at the target directory.
         *
         * @param dir      the target directory as given
         * @param target   the file the caller named
         * @param root     the handle on {@code dir}, closed with the walk
         * @param observer the observer of each directory creation, or {@code null} for none
         */
        Walk(Path dir, Path target, DirectoryHandle root, DirectoryCreationObserver observer) {
            this.dir = dir;
            this.target = target;
            this.observer = observer;
            entered.push(root);
        }

        /**
         * Walks the elements of {@code relative} to the entry they lead to, creating each missing directory on the
         * way in the directory handle the walk holds, after showing that directory to the observer, and following
         * the confined symbolic links met, at the last element too.
         *
         * @param relative the name, relative to the target directory
         * @return the entry the name leads to, which may not exist
         * @throws FileSystemException       naming a link that is not confined, with the reason
         *                                   {@value #LINK_NOT_CONFINED}; naming {@code target}, with the reason
         *                                   {@value #TOO_MANY_LINKS}, once more than {@value #MAX_SYMLINK_HOPS}
         *                                   links were met; with the reason {@value #IS_A_DIRECTORY} when the
         *                                   name leads to a directory; or naming a missing directory, with the
         *                                   reason {@value #NOT_CREATED_RELATIVE}, when it cannot be created
         *                                   relative to the directory held open to hold it
         * @throws FileAlreadyExistsException naming an entry on the way that is neither a directory nor a link
         * @throws AccessDeniedException      naming a directory on the way that the process may search but not
         *                                   read, where directories are held open
         * @throws IOException               when an entry cannot be read, a link cannot be read, a directory
         *                                   cannot be created or opened, or the observer throws it
         */
        Destination toFile(Path relative) throws IOException {
            Deque<Component> pending = new ArrayDeque<>();
            for (Path element : relative) {
                pending.addLast(new Component(element, null));
            }
            while (true) {
                Component component = pending.removeFirst();
                boolean last = pending.isEmpty();
                DirectoryHandle current = entered.peek();
                Path entry = current.path().resolve(component.name());
                String text = component.name().toString();
                if (text.equals("..")) {
                    if (!outside && entered.size() == 1) {
                        if (component.link() != null) {
                            throw notConfined(component.link());
                        }
                        outside = true;
                    }
                    if (last) {
                        throw new FileSystemException(entry.toString(), null, IS_A_DIRECTORY);
                    }
                    ascend(component.name());
                } else if (text.isEmpty() || text.equals(".")) {
                    if (last) {
                        throw new FileSystemException(entry.toString(), null, IS_A_DIRECTORY);
                    }
                } else {
                    BasicFileAttributes attributes = attributesOrNull(current, component.name());
                    if (attributes != null && attributes.isSymbolicLink()) {
                        follow(entry, pending);
                    } else if (last) {
                        return new Destination(current, component.name(), entry);
                    } else {
                        if (attributes == null) {
                            if (observer != null) {
                                observer.creatingDirectory(current.path());
                            }
                            current.createDirectory(component.name());
                        } else if (!attributes.isDirectory()) {
                            throw new FileAlreadyExistsException(entry.toString());
                        }
                        entered.push(current.openDirectory(component.name()));
                    }
                }
            }
        }

        /**
         * Steps to the parent of the current directory: back to the handle the walk came from while it is inside
         * the target directory, to the physical parent once it is outside.
         *
         * @param dotDot the {@code ..} element
         * @throws IOException when the parent cannot be opened or the current handle cannot be closed
         */
        private void ascend(Path dotDot) throws IOException {
            if (outside) {
                entered.push(entered.peek().openDirectory(dotDot));
            } else {
                entered.pop().close();
            }
        }

        /**
         * Follows the symbolic link {@code link} when it is confined: its text's elements are put before the
         * elements still to walk, relative text continuing from the link's own directory, and absolute text that
         * starts with the target directory continuing from the target directory.
         *
         * @param link    the link met
         * @param pending the elements still to walk
         * @throws FileSystemException when the walk is outside the target directory, the link's absolute text does
         *                             not start with it, or more than {@value #MAX_SYMLINK_HOPS} links were met
         * @throws IOException         when the link cannot be read or a handle cannot be closed
         */
        private void follow(Path link, Deque<Component> pending) throws IOException {
            if (outside) {
                throw notConfined(link);
            }
            if (hops == MAX_SYMLINK_HOPS) {
                throw new FileSystemException(target.toString(), null, TOO_MANY_LINKS);
            }
            hops++;
            Path text = Files.readSymbolicLink(link);
            Path rest = text;
            if (text.getRoot() != null) {
                rest = withinRoot(text);
                if (rest == null) {
                    throw notConfined(link);
                }
                while (entered.size() > 1) {
                    entered.pop().close();
                }
            }
            List<Path> elements = new ArrayList<>();
            for (Path element : rest) {
                elements.add(element);
            }
            for (int i = elements.size() - 1; i >= 0; i--) {
                pending.addFirst(new Component(elements.get(i), link));
            }
        }

        /**
         * Returns the part of absolute link text below the target directory, when the text starts with the target
         * directory's real path, or with its absolute path when that holds no {@code .} or {@code ..} segment.
         *
         * @param text the absolute link text
         * @return the text's elements after the prefix, the empty path for the directory itself, or {@code null}
         *         when the text starts with neither prefix
         * @throws IOException when the real path of the target directory cannot be read
         */
        private Path withinRoot(Path text) throws IOException {
            for (Path prefix : rootPrefixes()) {
                if (text.startsWith(prefix)) {
                    int prefixLength = prefix.getNameCount();
                    return text.getNameCount() == prefixLength
                            ? dir.getFileSystem().getPath("")
                            : text.subpath(prefixLength, text.getNameCount());
                }
            }
            return null;
        }

        /**
         * Returns the absolute paths that denote the target directory in link text, read once per write: its real
         * path, and its absolute path when that holds no {@code .} or {@code ..} segment and differs from it.
         *
         * @return the prefixes
         * @throws IOException when the real path of the target directory cannot be read
         */
        private List<Path> rootPrefixes() throws IOException {
            if (rootPrefixes == null) {
                Path real = dir.toRealPath();
                Path absolute = dir.toAbsolutePath();
                boolean lexical = true;
                for (Path element : absolute) {
                    String text = element.toString();
                    lexical &= !text.equals(".") && !text.equals("..");
                }
                rootPrefixes = lexical && !absolute.equals(real) ? List.of(real, absolute) : List.of(real);
            }
            return rootPrefixes;
        }

        /**
         * Returns the failure refusing the symbolic link {@code link}.
         *
         * @param link the link
         * @return the failure naming it, with the reason {@value #LINK_NOT_CONFINED}
         */
        private static FileSystemException notConfined(Path link) {
            return new FileSystemException(link.toString(), null, LINK_NOT_CONFINED);
        }

        /**
         * Closes every handle the walk holds, the target directory's last; the first failure is thrown with the
         * others suppressed on it.
         *
         * @throws IOException when a handle cannot be closed
         */
        @Override
        public void close() throws IOException {
            IOException failure = null;
            while (!entered.isEmpty()) {
                try {
                    entered.pop().close();
                } catch (IOException closeFailure) {
                    if (failure == null) {
                        failure = closeFailure;
                    } else {
                        failure.addSuppressed(closeFailure);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    /**
     * One path element still to walk.
     *
     * @param name the element
     * @param link the symbolic link whose text the element comes from, or {@code null} for an element of the name
     */
    private record Component(Path name, Path link) {
    }

    /**
     * The entry a walk led to.
     *
     * @param directory the handle of the directory holding it
     * @param name      its name in that directory
     * @param path      its full path, by which exceptions name it
     */
    private record Destination(DirectoryHandle directory, Path name, Path path) {
    }

    /**
     * The key of a destination's lock.
     *
     * @param directory the identity of the directory holding the destination
     * @param name      the destination's name in it
     */
    private record LockKey(Object directory, String name) {
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
