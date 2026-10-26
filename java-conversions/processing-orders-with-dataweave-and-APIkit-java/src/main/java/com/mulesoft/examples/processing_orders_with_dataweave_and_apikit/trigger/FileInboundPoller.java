/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.trigger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
 *   <li>reads the file and hands it to the {@link FileHandler}; a file that cannot be read is left in place and
 *       unmarked, so the next poll tries it again;</li>
 *   <li>after the handler returns or throws, moves the file to the move directory under its own name
 *       (copy-and-delete when a rename fails) or, without a move directory, deletes it.</li>
 * </ol>
 *
 * <p>The class neither schedules itself nor starts threads: the owning trigger calls {@link #pollOnce()} at the
 * endpoint's polling frequency. It is thread-safe; concurrent polls never hand the same file to the handler
 * twice.
 *
 * <p>It depends on the JDK and SLF4J alone, and names no project type, so that every file-triggered project can
 * copy it with only its {@code package} line changed.
 */
public class FileInboundPoller {

    private static final Logger LOG = LoggerFactory.getLogger(FileInboundPoller.class);

    /**
     * The flow a polled file is handed to. Any exception it throws is logged at ERROR by the poller and the file
     * is still consumed, as Mule's default exception strategy ends the flow normally.
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
            LOG.warn("Could not list input directory {}", inputDirectory);
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
     * @throws IOException when the file cannot be read
     */
    protected byte[] readContent(Path file) throws IOException {
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
            } catch (IOException | RuntimeException e) {
                LOG.warn("Could not open file {}, leaving it for the next poll: {}", path, e.toString());
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
                LOG.error("Exception processing file {}", name, e);
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
                    LOG.warn("Failed to delete file {}: it no longer exists", path);
                }
            } catch (IOException | RuntimeException e) {
                LOG.warn("Failed to delete file {}: {}", path, e.toString());
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
                        path, target, renameFailure.toString(), copyFailure.toString());
            }
        }
    }

    private static boolean createDirectory(Path directory) {
        try {
            Files.createDirectories(directory);
            return true;
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not create directory {}: {}", directory, e.toString());
            return false;
        }
    }
}
