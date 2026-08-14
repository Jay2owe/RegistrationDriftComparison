/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.ui;

import ij.measure.ResultsTable;
import regdrift.ArmOutcome;
import regdrift.Provenance;
import regdrift.Recommendation;
import regdrift.RegDriftEntry;
import regdrift.RegDriftResult;
import regdrift.RegDriftTables;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One screen a person can act on: what the recording does, whether it can be
 * registered, which engines the measurements support, and - when engines were
 * run - what each of them did to this recording.
 *
 * <h2>Three rules, and each is a place this could quietly become dishonest</h2>
 *
 * <ol>
 *   <li><b>Where the arbiter cannot separate two arms, that is what is shown</b>
 *       rather than a ranking with a tenth of a percentage point in it. Two arms
 *       inside the threshold carry the <em>same</em> rank number and the sentence
 *       saying which rank each could not be separated from. Rank 1 printed twice
 *       is the correct output here.</li>
 *   <li><b>The calibration flag sits beside the engine's name</b>, in the same
 *       line, rather than in a column somebody has to go and look for. And the
 *       sentence saying what the calibration set actually is - three IncuCyte
 *       phase-contrast seed frames spanning a stated range of structure - is on
 *       this screen, near the top, every time.</li>
 *   <li><b>An engine that is not on this computer gets a line with what to do
 *       about it</b>, never silence. A comparison that leaves out the engines
 *       somebody has not installed reads as a complete answer and is not.</li>
 * </ol>
 *
 * <h2>What this plugin actually offers for most engines</h2>
 *
 * <p>Two of the ten engines in the catalogue carry a measured timing and error;
 * the other eight were never run on the calibration's pixels, so their position
 * in the ranking is the catalogue's order and their rows say so in as many words.
 * What is measured for all ten is the comparison this plugin runs on the
 * <em>user's own</em> recording, and this screen is laid out that way: the
 * ranking says what was measured elsewhere, and the head-to-head says what
 * happened here.
 *
 * <h2>Text first, window second</h2>
 *
 * <p>{@link #lines(RegDriftResult)} builds every line this screen shows, and the
 * window draws exactly those lines and nothing else. So what a test asserts is
 * what a person reads, on a machine with no display, and the copyable text and
 * the screen cannot drift apart.
 */
public final class ResultsPanel {

    /** The window title. */
    public static final String TITLE = RegDriftEntry.DISPLAY_NAME + " - Results";

    /** What the head-to-head section is called. */
    public static final String HEAD_TO_HEAD_HEADING = "What each engine did on this recording";

    /** What the ranking section is called. */
    public static final String RANKING_HEADING = "What the measurements support";

    /** How a row states that the calibration does not cover this recording. */
    public static final String OUTSIDE_FLAG = "[outside calibrated range]";

    /** How a row states that no engine of this name was ever timed or scored. */
    public static final String NO_MEASURED_ROW = "no measured row";

    /** The line that says what the ranking is worth for engines nobody measured. */
    public static final String WHAT_IS_MEASURED_HERE =
            "Two of the engines below carry a measured error and timing. The rest were never run"
                    + " on the calibration set, so their place in the ranking is the catalogue's"
                    + " order and each row says so. What is measured for every engine is the"
                    + " head-to-head above, which ran on this recording.";

    private ResultsPanel() {
    }

    /**
     * Every line this screen shows, in order.
     *
     * <p>The whole panel as text. A blank entry is a blank line.
     *
     * @param result what a run produced. A run that gave up produces one line
     *               saying so
     */
    public static List<String> lines(RegDriftResult result) {
        List<String> out = new ArrayList<String>();
        if (result == null) return out;
        if (!result.isSuccess()) {
            out.add(result.failure().message());
            return out;
        }
        out.addAll(summaryLines(result));
        if (!result.arms().isEmpty()) {
            out.add("");
            out.add(HEAD_TO_HEAD_HEADING);
            out.addAll(armLines(result));
        }
        if (!result.ranked().isEmpty()) {
            out.add("");
            out.add(RANKING_HEADING);
            if (!result.arms().isEmpty()) out.add("  " + WHAT_IS_MEASURED_HERE);
            out.addAll(rankingLines(result));
        }
        return out;
    }

    /**
     * The three lines at the top: what moved, whether it can be registered, and
     * what the figures under it were measured on.
     *
     * <p>The calibration sentence is here rather than in a footnote because a
     * ranking read without it is a ranking read as more general than it is - see
     * defect D10.
     */
    public static List<String> summaryLines(RegDriftResult result) {
        List<String> out = new ArrayList<String>();
        ResultsTable diagnosis = result.diagnosis();
        int row = measuredRow(result);
        String label = cell(diagnosis, "motion_label", row);
        StringBuilder motion = new StringBuilder("Motion:      ");
        motion.append(label.isEmpty() ? "not measured" : label);
        String detail = motionDetail(diagnosis, row);
        if (!detail.isEmpty()) motion.append(" - ").append(detail);
        out.add(motion.toString());

        StringBuilder verdict = new StringBuilder("Verdict:     ");
        verdict.append(result.verdict() == null ? "not reached" : result.verdict().tableValue());
        String structure = structureDetail(diagnosis, row);
        if (!structure.isEmpty()) verdict.append(" - ").append(structure);
        out.add(verdict.toString());
        if (!result.verdictReason().isEmpty()) out.add("             " + result.verdictReason());

        String calibration = calibrationSentence(result);
        if (!calibration.isEmpty()) out.add("Calibration: " + calibration);
        return out;
    }

    /**
     * One line per arm, first ranked first, with the reason a shared rank is
     * shared and the install action on an engine that is not here.
     */
    public static List<String> armLines(RegDriftResult result) {
        List<String> out = new ArrayList<String>();
        for (ArmOutcome arm : result.arms()) {
            StringBuilder line = new StringBuilder("  rank ");
            line.append(pad(arm.rankColumn(), 3)).append("  ").append(arm.engineName());
            if (arm.hasFigure()) {
                line.append("  ").append(percent(arm.sdVsControlPercent()))
                        .append(" against the control");
                if (!Double.isNaN(arm.cpuSeconds())) {
                    line.append(", ").append(seconds(arm.cpuSeconds()))
                            .append(arm.cpuIsAFloor() ? " of processor time or more"
                                    : " of processor time");
                }
            } else {
                line.append("  ").append(arm.status().tableValue().replace('_', ' '));
            }
            out.add(line.toString());
            if (arm.sharesItsRank()) out.add("          " + arm.rankNote());
            if (arm.hasFigure() && !arm.separationText().isEmpty()) {
                out.add("          " + arm.separationText());
            }
            if (!arm.detail().isEmpty()) out.add("          " + arm.detail());
            if (arm.motionFlagged()) out.add("          " + arm.motionCaveat());
        }
        return out;
    }

    /**
     * One line per ranked engine, with the calibration flag beside the name and
     * the install action on an engine that is not here.
     */
    public static List<String> rankingLines(RegDriftResult result) {
        List<String> out = new ArrayList<String>();
        for (Recommendation ranked : result.ranked()) {
            StringBuilder line = new StringBuilder("  rank ");
            line.append(pad(Integer.toString(ranked.rank()), 3)).append("  ")
                    .append(ranked.engine());
            if (ranked.calibration() == Recommendation.Calibration.OUTSIDE_CALIBRATED_RANGE) {
                line.append(' ').append(OUTSIDE_FLAG);
            }
            if (Double.isNaN(ranked.expectedErrorPx())) {
                line.append("  ").append(NO_MEASURED_ROW);
            } else {
                line.append(String.format(Locale.US, "  expected mismatch %.2f px",
                        ranked.expectedErrorPx()));
                if (!Double.isNaN(ranked.expectedSeconds())) {
                    line.append(", about ").append(seconds(ranked.expectedSeconds()))
                            .append(" of processor time");
                }
            }
            out.add(line.toString());
            out.add("          " + ranked.reason());
            out.add("          " + installLine(ranked));
        }
        return out;
    }

    /** What this engine's presence is, and what to do about it when it is absent. */
    public static String installLine(Recommendation ranked) {
        if (ranked.presence() == Recommendation.Presence.PRESENT) {
            return "installed: yes. " + ranked.menuPath();
        }
        StringBuilder said = new StringBuilder("installed: ")
                .append(ranked.installedColumn().replace('_', ' '));
        if (!ranked.installAction().isEmpty()) {
            said.append(". ").append(ranked.installAction());
            if (ranked.installSizeMb() > 0) {
                said.append(String.format(Locale.US, " (about %.1f MB)", ranked.installSizeMb()));
            }
        }
        return said.toString();
    }

    /** What the figures were measured on, which belongs on the same screen as them. */
    public static String calibrationSentence(RegDriftResult result) {
        Provenance provenance = result.provenance();
        if (provenance == null) return "";
        return provenance.calibrationSet();
    }

    /** The whole screen as one block of text, for the clipboard. */
    public static String clipboardText(RegDriftResult result) {
        StringBuilder out = new StringBuilder(RegDriftEntry.DISPLAY_NAME).append('\n');
        for (String line : lines(result)) {
            out.append(line).append('\n');
        }
        String macro = macroLine(result);
        if (!macro.isEmpty()) {
            out.append('\n').append("Macro line for the engine ranked 1:\n").append(macro)
                    .append('\n');
        }
        return out.toString();
    }

    /** The copyable macro line for the engine ranked 1, or empty when there is none. */
    public static String macroLine(RegDriftResult result) {
        for (Recommendation ranked : result.ranked()) {
            if (ranked.rank() == 1 && !ranked.macroLine().isEmpty()) return ranked.macroLine();
        }
        return "";
    }

    /** True when there is something here worth a window. */
    public static boolean hasSomethingToShow(RegDriftResult result) {
        return result != null && result.isSuccess()
                && (!result.ranked().isEmpty() || !result.arms().isEmpty());
    }

    // ------------------------------------------------------------- the window

    /**
     * Puts the screen up.
     *
     * <p>Does nothing where there is no display, and nothing when the run
     * produced no ranking and no arm - the tables carry that case, and an empty
     * window is worse than none.
     */
    public static void show(final RegDriftResult result) {
        if (GraphicsEnvironment.isHeadless() || !hasSomethingToShow(result)) return;
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                build(result).setVisible(true);
            }
        });
    }

    /** Builds the window without showing it, which is what lets it be looked at in a test. */
    static JFrame build(final RegDriftResult result) {
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        Font fixed = new Font(Font.MONOSPACED, Font.PLAIN, 12);
        for (String line : lines(result)) {
            JLabel label = new JLabel(line.isEmpty() ? " " : line);
            label.setFont(fixed);
            label.setAlignmentX(0f);
            body.add(label);
        }

        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.setBorder(BorderFactory.createEmptyBorder(4, 14, 10, 14));
        buttons.add(copyButton("Copy this summary", clipboardText(result)));
        String macro = macroLine(result);
        if (!macro.isEmpty()) {
            buttons.add(Box.createHorizontalStrut(8));
            buttons.add(copyButton("Copy macro line", macro));
        }
        buttons.add(Box.createHorizontalGlue());

        JFrame frame = new JFrame(TITLE);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.getContentPane().setLayout(new BorderLayout());
        JScrollPane scroller = new JScrollPane(body);
        scroller.setBorder(null);
        frame.getContentPane().add(scroller, BorderLayout.CENTER);
        frame.getContentPane().add(buttons, BorderLayout.SOUTH);
        frame.pack();
        Dimension size = frame.getSize();
        frame.setSize(Math.min(size.width + 24, 1100), Math.min(size.height + 16, 760));
        frame.setLocationByPlatform(true);
        return frame;
    }

    private static JButton copyButton(String caption, final String text) {
        JButton button = new JButton(caption);
        button.setFocusPainted(false);
        button.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                try {
                    Toolkit.getDefaultToolkit().getSystemClipboard()
                            .setContents(new StringSelection(text), null);
                } catch (RuntimeException noClipboard) {
                    // A machine whose clipboard is refusing is one where the text is still on the
                    // screen to be selected by hand. There is nothing here for a person to act on.
                    return;
                }
            }
        });
        return button;
    }

    // -------------------------------------------------------------- the words

    /** Which diagnosis row describes the channel the run settled on. */
    private static int measuredRow(RegDriftResult result) {
        ResultsTable diagnosis = result.diagnosis();
        if (diagnosis == null || diagnosis.size() == 0) return -1;
        if (diagnosis.size() == 1) return 0;
        Provenance provenance = result.provenance();
        int channel = provenance == null ? Provenance.CHANNEL_UNRESOLVED : provenance.channel();
        for (int row = 0; row < diagnosis.size(); row++) {
            if (cell(diagnosis, "channel", row).equals(Integer.toString(channel))) return row;
        }
        return 0;
    }

    private static String motionDetail(ResultsTable diagnosis, int row) {
        List<String> parts = new ArrayList<String>();
        addNumber(parts, diagnosis, row, "drift_rate_px", "drift %.2f px per frame");
        addNumber(parts, diagnosis, row, "wander", "wander %.2f");
        addNumber(parts, diagnosis, row, "step_max_px", "largest step %.1f px");
        String knock = cell(diagnosis, "knock_present", row);
        if ("1".equals(knock)) parts.add("a knock is present");
        return join(parts);
    }

    private static String structureDetail(ResultsTable diagnosis, int row) {
        List<String> parts = new ArrayList<String>();
        String structure = cell(diagnosis, "localisability", row);
        String bin = cell(diagnosis, "measured_at_bin", row);
        if (!structure.isEmpty() && !bin.isEmpty()) {
            parts.add("localisability " + trim(structure) + " at bin " + bin);
        }
        addNumber(parts, diagnosis, row, "agreement_px",
                "the two estimators agree to %.2f px");
        return join(parts);
    }

    private static void addNumber(List<String> parts, ResultsTable diagnosis, int row,
                                  String column, String format) {
        String text = cell(diagnosis, column, row);
        if (text.isEmpty()) return;
        try {
            parts.add(String.format(Locale.US, format, Double.valueOf(Double.parseDouble(text))));
        } catch (NumberFormatException notANumber) {
            // A cell nothing filled reads as empty above; anything else is a column that changed
            // kind, which the table's own builder makes impossible.
            return;
        }
    }

    private static String cell(ResultsTable table, String column, int row) {
        return row < 0 ? "" : RegDriftTables.cellText(table, column, row);
    }

    private static String percent(double value) {
        return String.format(Locale.US, "%+.1f%%", value);
    }

    private static String seconds(double value) {
        if (Double.isNaN(value)) return "an unmeasured time";
        if (value < 90) return String.format(Locale.US, "%.1f s", value);
        return String.format(Locale.US, "%.0f min", value / 60.0);
    }

    private static String trim(String number) {
        try {
            return String.format(Locale.US, "%.4f", Double.valueOf(Double.parseDouble(number)));
        } catch (NumberFormatException notANumber) {
            return number;
        }
    }

    private static String join(List<String> parts) {
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (out.length() > 0) out.append(", ");
            out.append(part);
        }
        return out.toString();
    }

    private static String pad(String text, int width) {
        StringBuilder out = new StringBuilder(text);
        while (out.length() < width) out.append(' ');
        return out.toString();
    }
}
