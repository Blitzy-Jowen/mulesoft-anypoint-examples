/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;

/**
 * Writes a message payload to {@code <dir>/<name>} the way a Mule 3.8 {@code file:outbound-endpoint} on the default
 * file connector did: shared component SC-6 of the conversion plan.
 *
 * <ul>
 *   <li>The target directory is created, with any missing parents, before the write.</li>
 *   <li>An existing file of the same name is overwritten and truncated to the new content. None of the originals
 *       declares a {@code file:connector}, so {@code outputAppend} keeps its default {@code false}.</li>
 *   <li>Callers resolve the endpoint's {@code outputPattern} themselves and pass the resulting name; this class
 *       evaluates no pattern. An endpoint without an {@code outputPattern} used Mule's default name, which
 *       {@link #defaultFileName()} reproduces.</li>
 *   <li>Names are not sanitised: callers pass trusted, already resolved names, as the Mule endpoint received
 *       them, and a name is resolved against the directory with {@link Path#resolve(String)}.</li>
 *   <li>A relative directory resolves against the working directory, as Mule's relative {@code path} did.</li>
 *   <li>An {@link IOException} is never caught or wrapped here; it reaches the caller, whose flow handles it.</li>
 * </ul>
 *
 * <p>Copy rule: this project is the first consumer, so this class is the reference implementation. Every other
 * project with a {@code file:outbound-endpoint} carries a copy of it, and of its unit test, differing in the
 * {@code package} line alone; it therefore depends on the JDK and Spring's {@link Service} only, and never on a
 * project type.
 *
 * <p>Instances are stateless and safe to share between threads.
 */
@Service
public class FileOutboundWriter {

    /**
     * Creates a writer; it holds no state, so one instance serves every caller.
     */
    public FileOutboundWriter() {
    }

    /**
     * Writes {@code content} to {@code dir/name}, creating {@code dir} when missing and replacing any existing file.
     *
     * @param dir     the target directory, created with its missing parents
     * @param name    the resolved output file name
     * @param content the bytes to write, exactly as given
     * @return the written file, {@code dir.resolve(name)}
     * @throws IOException when the directory cannot be created or the file cannot be written
     */
    public Path write(Path dir, String name, byte[] content) throws IOException {
        Objects.requireNonNull(dir, "dir");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(content, "content");
        Files.createDirectories(dir);
        Path target = dir.resolve(name);
        Files.write(target, content,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        return target;
    }

    /**
     * Writes {@code content} encoded as UTF-8 to {@code dir/name}; see {@link #write(Path, String, byte[])}.
     *
     * @param dir     the target directory, created with its missing parents
     * @param name    the resolved output file name
     * @param content the text to write, encoded as UTF-8
     * @return the written file, {@code dir.resolve(name)}
     * @throws IOException when the directory cannot be created or the file cannot be written
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
     * @param name    the resolved output file name
     * @param content the bytes to write, exactly as given
     * @return the written file, {@code Path.of(dir).resolve(name)}
     * @throws IOException when the directory cannot be created or the file cannot be written
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
     * @param name    the resolved output file name
     * @param content the text to write, encoded as UTF-8
     * @return the written file, {@code Path.of(dir).resolve(name)}
     * @throws IOException when the directory cannot be created or the file cannot be written
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
}
