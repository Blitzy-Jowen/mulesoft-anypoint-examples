package com.mulesoft.examples.hello_world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Pairing of the files a {@code nondeterministic} fixture replay (ST-2) produced with the files the original left:
 * {@link FixtureParityTest#pairFileNames} pairs same-named files first and every other name by
 * {@link FixtureParityTest#fileNameShape}, in which a canonical UUID reads as {@link FixtureParityTest#UUID_TOKEN}, a
 * remaining digit run as {@code #}, and every other character as itself. The helpers are called alone, on literal
 * names, so none of the replay state {@link FixtureParityTest} keeps for its own run is touched.
 *
 * <p>Name lists are written with {@code ;} between names; an empty list is an empty directory.
 */
class FixtureParityFileStructureTest {

    private static final String WHAT = "post-greet3-age-under-18: directory Path_of_your_choice";

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "1b4e28ba-2fa1-11d2-883f-0016d3cca427.dat,          <uuid>.dat",
            "OUT-1B4E28BA-2FA1-11D2-883F-0016D3CCA427.DAT,      OUT-<uuid>.DAT",
            "out_1b4e28ba-2fa1-11d2-883f-0016d3cca427_7.dat,    out_<uuid>_#.dat",
            "'1b4e28ba-2fa1-11d2-883f-0016d3cca427-f47ac10b-58cc-4372-a567-0e02b2c3d479', <uuid>-<uuid>",
            "ShippingOrder-01-02-17_10-11-12.345.txt,           ShippingOrder-#-#-#_#-#-#.#.txt",
            "pic1500000000000.jpg,                              pic#.jpg",
            "'1b4e28b-2fa1-11d2-883f-0016d3cca427.dat',         '#b#e#b-#fa#-#d#-#f-#d#cca#.dat'",
            "'1b4e28bg-2fa1-11d2-883f-0016d3cca427.dat',        '#b#e#bg-#fa#-#d#-#f-#d#cca#.dat'",
            "'1b4e28ba2fa1-11d2-883f-0016d3cca427.dat',         '#b#e#ba#fa#-#d#-#f-#d#cca#.dat'",
            "'1b4e28ba-2fa1-11d2-883f-0016d3cca427a.dat',       '#b#e#ba-#fa#-#d#-#f-#d#cca#a.dat'",
            "'v1b4e28ba-2fa1-11d2-883f-0016d3cca427.dat',       'v#b#e#ba-#fa#-#d#-#f-#d#cca#.dat'",
            "'report#1<a>\\b.csv',                              'report\\##\\<a>\\\\b.csv'",
            "'<uuid>.dat',                                      '\\<uuid>.dat'",
    })
    void shapesANameByItsUuidsAndDigitRunsKeepingEveryOtherCharacter(String fileName, String shape) {
        assertEquals(shape, FixtureParityTest.fileNameShape(fileName));
    }

    @ParameterizedTest(name = "{0} pairs with {1}")
    @CsvSource({
            "1b4e28ba-2fa1-11d2-883f-0016d3cca427.dat,          f47ac10b-58cc-4372-a567-0e02b2c3d479.dat",
            "1B4E28BA-2FA1-11D2-883F-0016D3CCA427.dat,          f47ac10b-58cc-4372-a567-0e02b2c3d479.dat",
            "out-1b4e28ba-2fa1-11d2-883f-0016d3cca427.dat,      out-F47AC10B-58CC-4372-A567-0E02B2C3D479.dat",
            "out_1b4e28ba-2fa1-11d2-883f-0016d3cca427_1.dat,    out_f47ac10b-58cc-4372-a567-0e02b2c3d479_20.dat",
            "ShippingOrder-01-02-17_10-11-12.345.txt,           ShippingOrder-28-12-18_23-59-59.999.txt",
            "pic1500000000000.jpg,                              pic1712345678901.jpg",
    })
    void pairsNamesThatDifferOnlyInGeneratedParts(String expected, String actual) {
        assertEquals(Map.of(expected, actual),
                FixtureParityTest.pairFileNames(WHAT, Set.of(expected), Set.of(actual)));
    }

    @Test
    void pairsEachNameWithinItsShapeWhateverTheDirectorySortOrder() {
        Set<String> expected = Set.of("summary.txt", "f47ac10b-58cc-4372-a567-0e02b2c3d479.dat",
                "1b4e28ba-2fa1-11d2-883f-0016d3cca427.dat", "ShippingOrder-01-02-17_10-11-12.345.txt",
                "a-report-7.csv");
        Set<String> actual = Set.of("summary.txt", "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d.dat",
                "9f8e7d6c-5b4a-4938-8271-605f4e3d2c1b.dat", "ShippingOrder-28-12-18_23-59-59.999.txt",
                "a-report-12.csv");

        assertEquals(Map.of("summary.txt", "summary.txt",
                        "1b4e28ba-2fa1-11d2-883f-0016d3cca427.dat", "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d.dat",
                        "f47ac10b-58cc-4372-a567-0e02b2c3d479.dat", "9f8e7d6c-5b4a-4938-8271-605f4e3d2c1b.dat",
                        "ShippingOrder-01-02-17_10-11-12.345.txt", "ShippingOrder-28-12-18_23-59-59.999.txt",
                        "a-report-7.csv", "a-report-12.csv"),
                FixtureParityTest.pairFileNames(WHAT, expected, actual));
    }

    @ParameterizedTest(name = "original [{0}], replay [{1}]")
    @CsvSource({
            "out-1b4e28ba-2fa1-11d2-883f-0016d3cca427.dat, out-f47ac10b-58cc-4372-a567-0e02b2c3d479.txt",
            "a-1b4e28ba-2fa1-11d2-883f-0016d3cca427.dat,   b-f47ac10b-58cc-4372-a567-0e02b2c3d479.dat",
            "1b4e28ba-2fa1-11d2-883f-0016d3cca427.dat,     f47ac10-58cc-4372-a567-0e02b2c3d479.dat",
            "1b4e28ba-2fa1-11d2-883f-0016d3cca427.dat,     f47ac10g-58cc-4372-a567-0e02b2c3d479.dat",
            "1b4e28ba-2fa1-11d2-883f-0016d3cca427.dat,     f47ac10b58cc-4372-a567-0e02b2c3d479.dat",
            "1b4e28ba-2fa1-11d2-883f-0016d3cca427.dat,     f47ac10b-58cc-4372-a567-0e02b2c3d4790.dat",
            "1b4e28ba-2fa1-11d2-883f-0016d3cca427.dat,     '<uuid>.dat'",
            "'report-#.csv',                               report-1.csv",
            "1b4e28ba-2fa1-11d2-883f-0016d3cca427.dat,     "
                    + "'f47ac10b-58cc-4372-a567-0e02b2c3d479.dat;0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d.dat'",
            "'1b4e28ba-2fa1-11d2-883f-0016d3cca427.dat;0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d.dat', "
                    + "f47ac10b-58cc-4372-a567-0e02b2c3d479.dat",
            "1b4e28ba-2fa1-11d2-883f-0016d3cca427.dat,     ''",
            "'',                                           f47ac10b-58cc-4372-a567-0e02b2c3d479.dat",
            "'1b4e28ba-2fa1-11d2-883f-0016d3cca427.dat;0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d.dat', "
                    + "'f47ac10b-58cc-4372-a567-0e02b2c3d479.dat;notes.txt'",
    })
    void rejectsNamesThatDoNotPairByShape(String expectedNames, String actualNames) {
        Set<String> expected = names(expectedNames);
        Set<String> actual = names(actualNames);

        AssertionError mismatch = assertThrows(AssertionError.class,
                () -> FixtureParityTest.pairFileNames(WHAT, expected, actual));

        String message = mismatch.getMessage();
        assertTrue(message.startsWith(WHAT + " holds "), message);
        for (String fileName : expected) {
            assertTrue(message.contains(fileName), fileName + " is named: " + message);
            assertTrue(message.contains(FixtureParityTest.fileNameShape(fileName)), fileName + "'s shape: " + message);
        }
        for (String fileName : actual) {
            assertTrue(message.contains(fileName), fileName + " is named: " + message);
            assertTrue(message.contains(FixtureParityTest.fileNameShape(fileName)), fileName + "'s shape: " + message);
        }
    }

    private static Set<String> names(String list) {
        return list == null || list.isEmpty() ? Set.of() : Set.of(list.split(";"));
    }
}
