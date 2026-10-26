package com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests shared component SC-8, {@link WatermarkStore}: the Mule 3.8 poll watermark it reproduces (a default that is
 * evaluated on every poll and never persisted, the {@code MAX} selector over one poll's values, an empty poll leaving
 * the stored value untouched) and its file store (UTF-8 {@link Properties}, read afresh on every access, written whole
 * through a temporary file, surviving a restart, failing visibly on I/O errors), through the store's public API only.
 *
 * <p>Every consumer of SC-8 carries this class with only its {@code package} line changed, as it carries the store, so
 * it depends on the JDK, JUnit and AssertJ alone. Every file it touches lies under the test's {@link TempDir}.
 */
class WatermarkStoreTest {

    /** The watermark {@code variable} of the SC-8 originals. */
    private static final String KEY = "timestamp";

    /** The {@code default-expression} result a poll uses while no watermark is stored. */
    private static final String DEFAULT_WATERMARK = "YESTERDAY";

    private static final String JULY_3 = "2014-07-03T00:00:00.000Z";

    private static final String JULY_4 = "2014-07-04T06:16:54.000Z";

    private static final String JULY_5 = "2014-07-05T00:00:00.000Z";

    @TempDir
    Path tempDir;

    @Test
    void absentStoreFileResolvesToTheDefaultAndIsNotCreated() {
        WatermarkStore store = new WatermarkStore(storeFile());

        assertThat(store.retrieve(KEY, () -> DEFAULT_WATERMARK)).isEqualTo(DEFAULT_WATERMARK);
        assertThat(store.current(KEY)).isEmpty();
        assertThat(storeFile()).doesNotExist();
    }

    @Test
    void storeFileWithoutTheKeyResolvesToTheDefaultWithoutPersistingIt() throws IOException {
        WatermarkStore store = new WatermarkStore(storeFile());
        store.store("other", "x");

        assertThat(store.retrieve(KEY, () -> DEFAULT_WATERMARK)).isEqualTo(DEFAULT_WATERMARK);
        assertThat(store.current(KEY)).isEmpty();
        assertThat(readProperties(storeFile()))
                .doesNotContainKey(KEY)
                .containsEntry("other", "x")
                .hasSize(1);
    }

    @Test
    void defaultIsEvaluatedOnEveryCallUntilAValueIsStoredAndThenTheStoredValueWins() {
        WatermarkStore store = new WatermarkStore(storeFile());
        AtomicInteger evaluations = new AtomicInteger();
        Supplier<String> defaultValue = () -> {
            evaluations.incrementAndGet();
            return DEFAULT_WATERMARK;
        };

        assertThat(store.retrieve(KEY, defaultValue)).isEqualTo(DEFAULT_WATERMARK);
        assertThat(store.retrieve(KEY, defaultValue)).isEqualTo(DEFAULT_WATERMARK);
        assertThat(evaluations).hasValue(2);

        store.store(KEY, JULY_4);

        assertThat(store.retrieve(KEY, defaultValue)).isEqualTo(JULY_4);
        assertThat(evaluations).hasValue(2);
    }

    @Test
    void storedValueIsReturnedByCurrentAndRetrieveAndWrittenToTheFile() throws IOException {
        WatermarkStore store = new WatermarkStore(storeFile());

        store.store(KEY, JULY_4);

        assertThat(store.current(KEY)).contains(JULY_4);
        assertThat(store.retrieve(KEY, () -> DEFAULT_WATERMARK)).isEqualTo(JULY_4);
        assertThat(readProperties(storeFile())).containsEntry(KEY, JULY_4).hasSize(1);
    }

    @Test
    void storeOverwritesThePreviousValueOfTheKey() throws IOException {
        WatermarkStore store = new WatermarkStore(storeFile());
        store.store(KEY, JULY_4);

        store.store(KEY, JULY_5);

        assertThat(store.current(KEY)).contains(JULY_5);
        assertThat(readProperties(storeFile())).containsEntry(KEY, JULY_5).hasSize(1);
    }

    @Test
    void updateMaxStoresTheGreatestCandidateIgnoringNullAndBlankOnes() throws IOException {
        WatermarkStore store = new WatermarkStore(storeFile());
        List<String> candidates = Arrays.asList(JULY_4, null, "", "  ", JULY_5, JULY_3);

        assertThat(store.updateMax(KEY, candidates)).isTrue();

        assertThat(store.current(KEY)).contains(JULY_5);
        assertThat(readProperties(storeFile())).containsEntry(KEY, JULY_5);
    }

    @Test
    void updateMaxWithoutCandidatesStoresNothingAndCreatesNoFile() {
        WatermarkStore store = new WatermarkStore(storeFile());

        assertThat(store.updateMax(KEY, List.of())).isFalse();

        assertThat(store.current(KEY)).isEmpty();
        assertThat(storeFile()).doesNotExist();
    }

    @Test
    void updateMaxWithOnlyNullAndBlankCandidatesLeavesTheStoredValueAndFileUntouched() throws IOException {
        WatermarkStore store = new WatermarkStore(storeFile());
        store.store(KEY, JULY_4);
        byte[] before = Files.readAllBytes(storeFile());

        assertThat(store.updateMax(KEY, Arrays.asList(null, "", "  "))).isFalse();

        assertThat(store.current(KEY)).contains(JULY_4);
        assertThat(Files.readAllBytes(storeFile())).isEqualTo(before);
    }

    @Test
    void updateMaxComparesOnlyThisPollsCandidatesSoALowerMaximumReplacesTheStoredValue() {
        WatermarkStore store = new WatermarkStore(storeFile());
        store.store(KEY, JULY_5);

        assertThat(store.updateMax(KEY, List.of(JULY_3, JULY_4))).isTrue();

        assertThat(store.current(KEY)).contains(JULY_4);
    }

    @Test
    void keysAreStoredIndependently() throws IOException {
        WatermarkStore store = new WatermarkStore(storeFile());
        store.store("a", JULY_4);

        assertThat(store.updateMax("b", List.of("3"))).isTrue();

        assertThat(store.current("a")).contains(JULY_4);
        assertThat(store.current("b")).contains("3");
        assertThat(readProperties(storeFile()))
                .containsEntry("a", JULY_4)
                .containsEntry("b", "3")
                .hasSize(2);
    }

    @Test
    void storedValueSurvivesARestart() {
        new WatermarkStore(storeFile()).store(KEY, JULY_4);

        WatermarkStore restarted = new WatermarkStore(storeFile());

        assertThat(restarted.current(KEY)).contains(JULY_4);
        assertThat(restarted.retrieve(KEY, () -> DEFAULT_WATERMARK)).isEqualTo(JULY_4);
    }

    @Test
    void storeFileIsReadAfreshOnEveryAccess() {
        WatermarkStore first = new WatermarkStore(storeFile());
        WatermarkStore second = new WatermarkStore(storeFile());
        assertThat(first.current(KEY)).isEmpty();

        second.store(KEY, JULY_4);

        assertThat(first.current(KEY)).contains(JULY_4);

        first.store("a", JULY_5);

        assertThat(second.current(KEY)).contains(JULY_4);
        assertThat(second.current("a")).contains(JULY_5);
    }

    @Test
    void storeCreatesMissingParentDirectories() throws IOException {
        Path nested = tempDir.resolve("x").resolve("y").resolve("watermark.properties");
        WatermarkStore store = new WatermarkStore(nested);

        store.store(KEY, JULY_4);

        assertThat(nested).isRegularFile();
        assertThat(readProperties(nested)).containsEntry(KEY, JULY_4);
    }

    @Test
    void repeatedStoresLeaveNoTemporaryFileBehind() throws IOException {
        WatermarkStore store = new WatermarkStore(storeFile());

        for (int day = 1; day <= 7; day++) {
            store.store(KEY, String.format(Locale.ROOT, "2014-07-%02dT00:00:00.000Z", day));
        }

        assertThat(store.current(KEY)).contains("2014-07-07T00:00:00.000Z");
        try (Stream<Path> files = Files.list(tempDir)) {
            assertThat(files.map(file -> file.getFileName().toString())).containsExactly("watermark.properties");
        }
    }

    @Test
    void nonAsciiValueRoundTripsThroughTheUtf8File() throws IOException {
        new WatermarkStore(storeFile()).store(KEY, "Zürich-✓");

        assertThat(new WatermarkStore(storeFile()).current(KEY)).contains("Zürich-✓");
        assertThat(readProperties(storeFile())).containsEntry(KEY, "Zürich-✓");
    }

    @Test
    void storePathThatCannotHoldTheFileFailsEveryAccessInsteadOfResolvingToTheDefault() throws IOException {
        Path blocker = Files.createFile(tempDir.resolve("blocker"));
        WatermarkStore store = new WatermarkStore(blocker.resolve("watermark.properties"));

        assertThatThrownBy(() -> store.store(KEY, JULY_4)).isInstanceOf(UncheckedIOException.class);
        assertThatThrownBy(() -> store.current(KEY)).isInstanceOf(UncheckedIOException.class);
        assertThatThrownBy(() -> store.retrieve(KEY, () -> DEFAULT_WATERMARK))
                .isInstanceOf(UncheckedIOException.class);
        assertThat(blocker).isRegularFile().isEmptyFile();
    }

    @Test
    void failedWriteThrowsUncheckedIOExceptionAndLeavesNoFileBehind() throws IOException {
        // A dangling link reads as an absent store, but its directory cannot be created, so only the write fails.
        Path danglingDirectory = Files.createSymbolicLink(tempDir.resolve("dangling"), tempDir.resolve("missing"));
        WatermarkStore store = new WatermarkStore(danglingDirectory.resolve("watermark.properties"));
        assertThat(store.current(KEY)).isEmpty();

        assertThatThrownBy(() -> store.store(KEY, JULY_4)).isInstanceOf(UncheckedIOException.class);

        assertThat(tempDir.resolve("missing")).doesNotExist();
        try (Stream<Path> files = Files.list(tempDir)) {
            assertThat(files.map(file -> file.getFileName().toString())).containsExactly("dangling");
        }
    }

    private Path storeFile() {
        return tempDir.resolve("watermark.properties");
    }

    private static Properties readProperties(Path file) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }
}
