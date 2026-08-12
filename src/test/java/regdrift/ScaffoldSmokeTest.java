/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift;

import ij.plugin.PlugIn;
import org.junit.Test;
import regdrift.internal.CoreProbe;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Proves the scaffold: the test harness runs, both cores resolve and answer a
 * real call, both menu entries name classes that exist and implement
 * {@code PlugIn}, and {@code plugins.config} is in the encoding Fiji can read.
 *
 * <p>These classes are unshaded here — {@code mvn test} runs before the shade
 * plugin does — so the relocation itself is checked in the exit gate, against
 * the packaged jar. What this test rules out is the failure that looks
 * identical from the outside: the cores not being on the classpath at all.
 */
public class ScaffoldSmokeTest {

    private static final String CONFIG = "plugins.config";

    private static final String[][] ENTRIES = {
            {"Plugins>Registration", "Compare Registration Methods...",
                    "regdrift.CompareRegistration_"},
            {"Plugins>Registration", "Registration Diagnostics...",
                    "regdrift.RegistrationDiagnostics_"},
    };

    @Test
    public void oc3dCoreIsOnTheClasspathAndAnswers() {
        assertEquals("yes", CoreProbe.oc3dCoreCall());
        assertCoreClass(CoreProbe.oc3dCoreClassName(), "oc3d.core.macro.MacroOptions", "core.macro.MacroOptions");
    }

    @Test
    public void autofixCoreIsOnTheClasspathAndAnswers() {
        assertEquals("registrationdriftcomparison", CoreProbe.autofixCoreCall());
        assertCoreClass(CoreProbe.autofixCoreClassName(), "autofix.core.Product", "autofix.Product");
    }

    /**
     * The shared toggle widget comes from {@code oc3d-core}, not from a
     * verbatim copy of CPC's. Stage 04 builds the dialogs on top of this.
     */
    @Test
    public void toggleSwitchComesFromTheRelocatedCore() {
        assertCoreClass(CoreProbe.toggleSwitchClassName(),
                "oc3d.core.ui.ToggleSwitch", "core.ui.ToggleSwitch");
    }

    @Test
    public void pluginsConfigIsPlainAsciiWithATrailingNewline() throws IOException {
        byte[] bytes = readConfigBytes();
        assertTrue("plugins.config is empty", bytes.length > 0);
        assertFalse("plugins.config starts with a UTF-8 BOM; Fiji skips the entries with no error",
                bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF
                        && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF);
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xFF;
            assertTrue("plugins.config byte " + i + " is not plain ASCII: 0x"
                    + Integer.toHexString(b), b < 0x80);
        }
        assertEquals("plugins.config needs a trailing newline",
                '\n', (char) (bytes[bytes.length - 1] & 0xFF));
    }

    @Test
    public void pluginsConfigDeclaresBothMenuEntries() throws IOException {
        List<String> lines = readConfigLines();
        assertEquals("plugins.config should hold exactly the two menu entries",
                ENTRIES.length, lines.size());
        for (int i = 0; i < ENTRIES.length; i++) {
            String expected = ENTRIES[i][0] + ", \"" + ENTRIES[i][1] + "\", " + ENTRIES[i][2];
            assertEquals(expected, lines.get(i));
        }
    }

    @Test
    public void everyDeclaredEntryClassExistsAndIsAPlugIn() throws Exception {
        for (String[] entry : ENTRIES) {
            Class<?> type = Class.forName(entry[2]);
            assertTrue(entry[2] + " must implement ij.plugin.PlugIn",
                    PlugIn.class.isAssignableFrom(type));
            // Fiji instantiates it with the no-argument constructor.
            assertTrue(entry[2] + " must be instantiable by Fiji",
                    type.newInstance() instanceof PlugIn);
        }
    }

    /**
     * The class name must sit under one of the two prefixes the shade plugin
     * moves between, and must keep its tail. Anything else means the
     * relocation pattern and the source package have drifted apart.
     */
    private static void assertCoreClass(String actual, String unshadedTail, String shadedTail) {
        boolean unshaded = actual.equals(CoreProbe.UNSHADED_PREFIX + unshadedTail);
        boolean shaded = actual.equals(CoreProbe.SHADED_PREFIX + shadedTail);
        assertTrue("expected " + CoreProbe.UNSHADED_PREFIX + unshadedTail + " (before packaging) or "
                + CoreProbe.SHADED_PREFIX + shadedTail + " (after), but found " + actual,
                unshaded || shaded);
    }

    private static List<String> readConfigLines() throws IOException {
        String text = new String(readConfigBytes(), "US-ASCII");
        List<String> lines = new ArrayList<String>();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) lines.add(trimmed);
        }
        return lines;
    }

    private static byte[] readConfigBytes() throws IOException {
        InputStream in = ScaffoldSmokeTest.class.getClassLoader().getResourceAsStream(CONFIG);
        assertTrue(CONFIG + " is not on the classpath", in != null);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}
