package com.mulesoft.examples.hello_world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Containment of the fixture replay's directories (ST-2): {@link FixtureParityTest#confine} decides whether a
 * directory key, {@code request.file.directory}, directory-spec property or seeded file's directory lies inside the
 * replay's temporary root. The helper is called alone, on JUnit temporary directories, so none of the replay state
 * {@link FixtureParityTest} keeps for its own run is touched.
 *
 * <p>Candidates are written as templates: {@code {root}} is the replay root, {@code {in}} the directory
 * {@code ${dir:in}} resolves to ({@code <root>/in}), and {@code {outside}} a directory outside the root.
 */
class FixtureParityDirectoryTest {

    private static final String WHAT = "Fixture containment-check: directory spec";

    @TempDir
    Path root;

    @TempDir
    Path outside;

    @BeforeEach
    void createDirIn() throws IOException {
        Files.createDirectories(root.resolve("in"));
    }

    @ParameterizedTest(name = "{0} is <root>/{1}")
    @CsvSource({
            "'{in}/sub',         in/sub",
            "'{in}/./sub/',      in/sub",
            "'{in}/sub/../sub2', in/sub2",
            "'{in}/../other',    other",
            "in/sub,             in/sub",
    })
    void returnsTheFinalDirectoryInsideTheRoot(String candidate, String expected) throws IOException {
        Path directory = FixtureParityTest.confine(root, path(candidate), WHAT);

        assertEquals(inside(expected), directory, "the final directory, which the replay creates and registers");
        assertFalse(Files.exists(directory), "confining creates nothing");
        Files.createDirectories(directory);
        assertEquals(directory, FixtureParityTest.confine(root, directory, WHAT), "still confined once created");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "{in}/../../outside",
            "{in}/../..",
            "../escape",
            "{outside}/in",
            "{root}-sibling/in",
            "{root}",
            "{in}/..",
            ".",
    })
    void rejectsADirectoryOutsideTheRootOrTheRootItself(String candidate) {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> FixtureParityTest.confine(root, path(candidate), WHAT));

        assertNamesSpecAndRoot(failure);
    }

    @ParameterizedTest(name = "<root>/{0} -> {1}: {2}")
    @CsvSource({
            "escape, '{outside}',         '{root}/escape'",
            "escape, '{outside}',         '{root}/escape/sub'",
            "escape, '{outside}',         '{in}/../escape/sub'",
            "self,   '{root}',            '{root}/self'",
            "broken, '{outside}/missing', '{root}/broken/sub'",
    })
    void rejectsASymbolicLinkOutOfTheRoot(String link, String target, String candidate) throws IOException {
        link(root.resolve(link), path(target));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> FixtureParityTest.confine(root, path(candidate), WHAT));

        assertNamesSpecAndRoot(failure);
        try (Stream<Path> entries = Files.list(outside)) {
            assertEquals(0, entries.count(), "nothing is created outside the root");
        }
    }

    @Test
    void acceptsASymbolicLinkThatStaysInsideTheRoot() throws IOException {
        link(root.resolve("alias"), root.resolve("in"));

        assertEquals(inside("alias/sub"), FixtureParityTest.confine(root, path("{root}/alias/sub"), WHAT));
    }

    @Test
    void acceptsDirectoriesOfARootReachedThroughASymbolicLink() throws IOException {
        Path linkedRoot = outside.resolve("linked-root");
        link(linkedRoot, root);

        Path directory = FixtureParityTest.confine(linkedRoot, linkedRoot.resolve("in/sub"), WHAT);
        assertEquals(linkedRoot.toAbsolutePath().normalize().resolve("in/sub"), directory);
        Files.createDirectories(directory);
        assertEquals(directory, FixtureParityTest.confine(linkedRoot, directory, WHAT));
    }

    private void assertNamesSpecAndRoot(IllegalStateException failure) {
        String message = failure.getMessage();
        assertTrue(message.contains(WHAT), message);
        assertTrue(message.contains("inside the replay root ") && message.contains(root.getFileName().toString()),
                message);
    }

    /** The candidate a template stands for, with the names of the class comment substituted. */
    private Path path(String template) {
        return Path.of(template.replace("{in}", root.resolve("in").toString())
                .replace("{root}", root.toString())
                .replace("{outside}", outside.toString()));
    }

    private Path inside(String relative) {
        return root.toAbsolutePath().normalize().resolve(relative);
    }

    private static void link(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            Assumptions.abort("The file system cannot create symbolic links: " + e);
        }
    }
}
