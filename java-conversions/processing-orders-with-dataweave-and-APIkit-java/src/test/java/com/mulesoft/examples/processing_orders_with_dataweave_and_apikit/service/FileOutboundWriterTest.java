/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit test of {@link FileOutboundWriter} (shared component SC-6): the Mule 3.8 {@code file:outbound-endpoint}
 * write to {@code <dir>/<name>}, creating the directory, overwriting an existing file, writing bytes exactly and text
 * as UTF-8, resolving a relative directory against the working directory, letting every {@link IOException} reach
 * the caller, and the default {@code <uuid>.dat} name.
 *
 * <p>Every project that carries {@code FileOutboundWriter} carries this test with only the {@code package} line
 * changed, so it depends on the writer, JUnit 5, AssertJ and the JDK alone: no Spring context, no mock, no project
 * type and no resource file. Every file it writes lands under its {@link TempDir}.
 */
class FileOutboundWriterTest {

    private static final Pattern DEFAULT_NAME =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\.dat");

    /** The four {@code write} overloads, each called with its own directory and content types. */
    private enum Overload {
        PATH_BYTES, PATH_STRING, STRING_BYTES, STRING_STRING;

        Path write(FileOutboundWriter writer, Path dir, String name, String content) throws IOException {
            byte[] bytes = content == null ? null : content.getBytes(StandardCharsets.UTF_8);
            String configuredDir = dir == null ? null : dir.toString();
            return switch (this) {
                case PATH_BYTES -> writer.write(dir, name, bytes);
                case PATH_STRING -> writer.write(dir, name, content);
                case STRING_BYTES -> writer.write(configuredDir, name, bytes);
                case STRING_STRING -> writer.write(configuredDir, name, content);
            };
        }
    }

    @TempDir
    Path tempDir;

    private final FileOutboundWriter writer = new FileOutboundWriter();

    @ParameterizedTest
    @EnumSource(Overload.class)
    @DisplayName("a missing directory and its missing parents are created; dir/name is written and returned")
    void createsMissingDirectoriesAndReturnsTheWrittenFile(Overload overload) throws IOException {
        Path dir = tempDir.resolve("output").resolve("2016").resolve("orders");

        Path written = overload.write(writer, dir, "orders.json", "{}");

        assertThat(dir).isDirectory();
        assertThat(written).isEqualTo(dir.resolve("orders.json")).isRegularFile();
        assertThat(Files.readAllBytes(written)).isEqualTo(new byte[] {'{', '}'});
        try (Stream<Path> listing = Files.list(dir)) {
            assertThat(listing).containsExactly(written);
        }
    }

    @ParameterizedTest
    @EnumSource(Overload.class)
    @DisplayName("an existing file of the same name is replaced and truncated; other files in the directory stay")
    void overwritesAndTruncatesAnExistingFile(Overload overload) throws IOException {
        Path dir = Files.createDirectories(tempDir.resolve("output"));
        Path existing = Files.writeString(dir.resolve("report.csv"), "an older and much longer report\n");
        Path sibling = Files.writeString(dir.resolve("orders.json"), "untouched");

        Path written = overload.write(writer, dir, "report.csv", "new\n");

        assertThat(written).isEqualTo(existing);
        assertThat(Files.readAllBytes(existing)).isEqualTo(new byte[] {'n', 'e', 'w', '\n'});
        assertThat(Files.readString(sibling)).isEqualTo("untouched");
    }

    static Stream<Arguments> byteContents() {
        byte[] everyByteValue = new byte[256];
        for (int i = 0; i < everyByteValue.length; i++) {
            everyByteValue[i] = (byte) i;
        }
        byte[] crLfWithoutTrailingLineEnd = {'a', '\r', '\n', 'b'};
        return Stream.of(
                Arguments.of("every byte value 0x00 to 0xFF, Path directory", true, everyByteValue),
                Arguments.of("every byte value 0x00 to 0xFF, String directory", false, everyByteValue),
                Arguments.of("CR LF kept and no line end appended, Path directory", true, crLfWithoutTrailingLineEnd),
                Arguments.of("CR LF kept and no line end appended, String directory", false,
                        crLfWithoutTrailingLineEnd),
                Arguments.of("empty content -> empty file, Path directory", true, new byte[0]),
                Arguments.of("empty content -> empty file, String directory", false, new byte[0]));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("byteContents")
    @DisplayName("byte content is written exactly as given: no encoding, no line-end conversion, nothing appended")
    void writesBytesExactly(String description, boolean pathDirectory, byte[] content) throws IOException {
        Path dir = tempDir.resolve("output");

        Path written = pathDirectory
                ? writer.write(dir, "payload.dat", content)
                : writer.write(dir.toString(), "payload.dat", content);

        assertThat(Files.readAllBytes(written)).isEqualTo(content);
    }

    @ParameterizedTest
    @EnumSource(value = Overload.class, names = {"PATH_STRING", "STRING_STRING"})
    @DisplayName("String content is encoded as UTF-8 with no byte order mark, whatever the platform charset")
    void encodesStringContentAsUtf8(Overload overload) throws IOException {
        String text = "Jos" + cp(0xE9) + " " + cp(0x20AC) + " " + cp(0x1F600) + "\r\n";

        Path written = overload.write(writer, tempDir.resolve("output"), "text.txt", text);

        assertThat(Files.readAllBytes(written)).isEqualTo(bytes(
                'J', 'o', 's', 0xC3, 0xA9, ' ', 0xE2, 0x82, 0xAC, ' ', 0xF0, 0x9F, 0x98, 0x80, '\r', '\n'));
    }

    @ParameterizedTest
    @EnumSource(value = Overload.class, names = {"STRING_BYTES", "STRING_STRING"})
    @DisplayName("a relative directory string resolves against the working directory, and the relative file is returned")
    void resolvesARelativeDirectoryAgainstTheWorkingDirectory(Overload overload) throws IOException {
        // Real paths on both sides, so the ".." steps walk the physical parents the operating system follows.
        Path workingDirectory = Path.of("").toAbsolutePath().toRealPath();
        Path target = tempDir.toRealPath().resolve("relative-output");
        Path relative = workingDirectory.relativize(target);

        Path written = overload.write(writer, relative, "orders.json", "{}");

        assertThat(relative.isAbsolute()).isFalse();
        assertThat(written).isEqualTo(relative.resolve("orders.json"));
        assertThat(Files.readString(target.resolve("orders.json"))).isEqualTo("{}");
    }

    @ParameterizedTest
    @EnumSource(Overload.class)
    @DisplayName("an IOException from creating the directory or writing the file reaches the caller, nothing changes")
    void letsEveryIOExceptionReachTheCaller(Overload overload) throws IOException {
        Path regularFile = Files.writeString(tempDir.resolve("output"), "a regular file");
        Path existingDirectory = Files.createDirectory(tempDir.resolve("existing-directory"));
        Path inside = Files.writeString(existingDirectory.resolve("inside.txt"), "kept");

        assertThatThrownBy(() -> overload.write(writer, regularFile, "orders.json", "payload"))
                .as("directory path is an existing regular file")
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> overload.write(writer, tempDir, "existing-directory", "payload"))
                .as("file name is an existing directory")
                .isInstanceOf(IOException.class);

        assertThat(Files.readString(regularFile)).isEqualTo("a regular file");
        assertThat(existingDirectory).isDirectory();
        try (Stream<Path> listing = Files.list(existingDirectory)) {
            assertThat(listing).containsExactly(inside);
        }
        assertThat(Files.readString(inside)).isEqualTo("kept");
    }

    static Stream<Arguments> nullArguments() {
        return Stream.of(Overload.values()).flatMap(overload -> Stream.of(
                Arguments.of(overload, "dir"),
                Arguments.of(overload, "name"),
                Arguments.of(overload, "content")));
    }

    @ParameterizedTest(name = "{0}: null {1}")
    @MethodSource("nullArguments")
    @DisplayName("a null dir, name or content -> NullPointerException naming it, before anything is created")
    void rejectsNullArguments(Overload overload, String nullArgument) {
        Path dir = "dir".equals(nullArgument) ? null : tempDir.resolve("output");
        String name = "name".equals(nullArgument) ? null : "orders.json";
        String content = "content".equals(nullArgument) ? null : "{}";

        assertThatThrownBy(() -> overload.write(writer, dir, name, content))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessage(nullArgument);
        assertThat(tempDir).isEmptyDirectory();
    }

    @Test
    @DisplayName("defaultFileName: a fresh random UUID followed by .dat on every call, usable as the written name")
    void defaultFileNameIsAFreshRandomUuidWithDatExtension() throws IOException {
        Set<String> names = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            String name = FileOutboundWriter.defaultFileName();
            UUID uuid = UUID.fromString(name.substring(0, name.length() - ".dat".length()));

            assertThat(name).matches(DEFAULT_NAME).isEqualTo(uuid + ".dat");
            assertThat(uuid.version()).isEqualTo(4);
            assertThat(uuid.variant()).isEqualTo(2);
            names.add(name);
        }
        assertThat(names).hasSize(100);

        String name = FileOutboundWriter.defaultFileName();
        Path written = writer.write(tempDir, name, new byte[] {1});

        assertThat(written.getFileName()).hasToString(name);
        assertThat(Files.readAllBytes(written)).isEqualTo(new byte[] {1});
    }

    private static String cp(int codePoint) {
        return Character.toString(codePoint);
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (byte) values[i];
        }
        return result;
    }
}
