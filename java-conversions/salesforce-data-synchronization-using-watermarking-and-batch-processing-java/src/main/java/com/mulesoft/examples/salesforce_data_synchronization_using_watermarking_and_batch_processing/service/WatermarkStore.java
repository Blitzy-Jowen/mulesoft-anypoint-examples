package com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.service;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * File-backed watermark store replacing the persistent object store behind a Mule 3.8 poll {@code watermark}.
 *
 * <p>SC-8 shared component, reference copy. Every other consumer copies this file changing only the {@code package}
 * line, so it names no project type and reads its one setting, {@code watermark.store-path}, itself.
 *
 * <p>Reproduced watermark semantics:
 * <ul>
 *   <li>one value per key (the watermark {@code variable}), persisted so it survives a restart as Mule's persistent
 *       object store did;</li>
 *   <li>while no value is stored, the watermark resolves to its {@code default-expression}, evaluated again on every
 *       poll and never persisted ({@link #retrieve});</li>
 *   <li>selector {@code MAX}: once the poll's processing completes, the store takes the greatest value the poll
 *       selected, compared only among that poll's values; a poll that selected nothing leaves the stored value
 *       untouched ({@link #updateMax}). Calling it only after processing is the caller's responsibility.</li>
 * </ul>
 *
 * <p>The store is a UTF-8 {@link Properties} file. It is read afresh on every access, so a value written by another
 * instance or edited by hand is seen at once, and every write replaces the whole file through a temporary file in the
 * same directory, so a reader never sees a partly written store. Values are defined by that round trip, not by the
 * file's text: {@link Properties#store} escapes {@code :} and {@code =}.
 *
 * <p>All public methods are {@code synchronized}, so one instance serves concurrent callers; separate processes sharing
 * one file are not coordinated, as Mule's object store did not coordinate them either.
 */
@Component
public class WatermarkStore {

    private static final String STORE_COMMENT = "Mule watermark object store";

    private static final String TEMPORARY_FILE_SUFFIX = ".tmp";

    private final Path storePath;

    /**
     * Creates a store kept in the file {@code storePath}; neither the file nor its directories need to exist yet.
     *
     * @param storePath the store file, resolved against the working directory when relative
     * @throws NullPointerException     if {@code storePath} is {@code null}
     * @throws IllegalArgumentException if {@code storePath} names no file, such as a file-system root
     */
    public WatermarkStore(@Value("${watermark.store-path}") Path storePath) {
        Objects.requireNonNull(storePath, "storePath must not be null");
        Path absolute = storePath.toAbsolutePath().normalize();
        if (absolute.getFileName() == null) {
            throw new IllegalArgumentException("Watermark store path names no file: " + storePath);
        }
        this.storePath = absolute;
    }

    /**
     * Returns the absolute, normalized path of the store file.
     *
     * @return the store file path
     */
    public Path storePath() {
        return storePath;
    }

    /**
     * Returns the value stored under {@code key}.
     *
     * @param key the watermark variable name
     * @return the stored value, or empty when the store holds none for {@code key}
     * @throws NullPointerException if {@code key} is {@code null}
     * @throws UncheckedIOException if the store file exists but cannot be read
     */
    public synchronized Optional<String> current(String key) {
        Objects.requireNonNull(key, "key must not be null");
        return Optional.ofNullable(load().getProperty(key));
    }

    /**
     * Returns the value stored under {@code key}, or the default while none is stored.
     *
     * <p>The default is the watermark's {@code default-expression}: it is evaluated on every call that finds no stored
     * value and is never written to the store.
     *
     * @param key          the watermark variable name
     * @param defaultValue supplies the value to use while none is stored
     * @return the stored value, or the value {@code defaultValue} supplies
     * @throws NullPointerException if an argument is {@code null}, or {@code defaultValue} supplies {@code null}
     * @throws UncheckedIOException if the store file exists but cannot be read
     */
    public synchronized String retrieve(String key, Supplier<String> defaultValue) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(defaultValue, "defaultValue must not be null");
        String stored = load().getProperty(key);
        if (stored != null) {
            return stored;
        }
        return Objects.requireNonNull(defaultValue.get(),
                () -> "Default watermark for key '" + key + "' must not be null");
    }

    /**
     * Applies the {@code MAX} selector: stores the greatest of this poll's selected values under {@code key}.
     *
     * <p>{@code null} and blank candidates are ignored, as Mule ignores a selector expression that yields nothing. The
     * remaining candidates are compared in natural {@link String} order, which is chronological for the ISO-8601 UTC
     * timestamps the selector reads. The previously stored value takes no part in the comparison, because Mule's
     * selector only sees the values of the current poll.
     *
     * @param key        the watermark variable name
     * @param candidates the selector values of this poll's records; elements may be {@code null}
     * @return {@code true} if a value was stored, {@code false} if no candidate remained and the store was left
     *         untouched
     * @throws NullPointerException if {@code key} or {@code candidates} is {@code null}
     * @throws UncheckedIOException if the store cannot be read or written
     */
    public synchronized boolean updateMax(String key, Collection<String> candidates) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(candidates, "candidates must not be null");
        Optional<String> max = candidates.stream()
                .filter(Objects::nonNull)
                .filter(candidate -> !candidate.isBlank())
                .max(Comparator.naturalOrder());
        if (max.isEmpty()) {
            return false;
        }
        store(key, max.get());
        return true;
    }

    /**
     * Stores {@code value} under {@code key}, keeping every other key of the store.
     *
     * @param key   the watermark variable name
     * @param value the value to store
     * @throws NullPointerException if an argument is {@code null}
     * @throws UncheckedIOException if the store cannot be read or written
     */
    public synchronized void store(String key, String value) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(value, "value must not be null");
        Properties properties = load();
        properties.setProperty(key, value);
        write(properties);
    }

    /**
     * Reads the whole store; a missing file is an empty store.
     *
     * <p>The file is opened directly rather than tested with {@link Files#exists} first: a file removed in between is
     * still an empty store, and a path that cannot hold the file (a parent that is a regular file, an unreadable
     * directory) fails visibly instead of silently resolving every poll to the default watermark.
     */
    private Properties load() {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(storePath, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (NoSuchFileException absent) {
            return properties;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read watermark store " + storePath, e);
        }
        return properties;
    }

    /** Replaces the store file with {@code properties}, creating missing directories first. */
    private void write(Properties properties) {
        Path directory = storePath.getParent();
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, storePath.getFileName().toString(), TEMPORARY_FILE_SUFFIX);
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                properties.store(writer, STORE_COMMENT);
            }
            moveIntoPlace(temporary);
        } catch (IOException e) {
            UncheckedIOException failure = new UncheckedIOException("Cannot write watermark store " + storePath, e);
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
    }

    private void moveIntoPlace(Path temporary) throws IOException {
        try {
            Files.move(temporary, storePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, storePath, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
