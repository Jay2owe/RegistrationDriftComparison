/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.ui;

import regdrift.autofix.AutofixService;
import regdrift.autofix.EngineId;
import regdrift.harness.EngineDescriptor;
import regdrift.harness.EngineRunner;
import sc.fiji.autofix.core.DependencyFixResult;
import sc.fiji.autofix.core.DependencyServiceCore;
import sc.fiji.autofix.core.DependencySpec;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;

/**
 * The Engines section: one line per registration engine, what was found for it,
 * and either a button that installs it or the sentence saying why there is no
 * button.
 *
 * <h2>Where the words come from</h2>
 *
 * <p>Every word about an engine - the state, the detail beneath it, the button
 * caption with its download size, the sentence on a row nobody can repair from
 * here - is finished text handed over by the shared dependency machinery. This
 * class lays those strings out and writes none of its own about any engine. Two
 * sibling plugins do the same with the same machinery, so all three say the same
 * thing about the same situation while looking nothing alike.
 *
 * <p>The section's own furniture - its heading, its column titles, and the two
 * promises at the foot - is this plugin's, because it is about this plugin
 * rather than about anybody's dependency.
 *
 * <h2>The section appears first and fills in afterwards</h2>
 *
 * <p>Reading ten engines off a full Fiji install measured about five seconds,
 * nearly all of it inside the shared machinery's file checks. Doing that before
 * the window appeared would mean a window that appeared five seconds after
 * somebody chose the menu item, so the section is drawn with every row marked as
 * being checked and is filled in from another thread as the answers arrive. A
 * second opening in the same session has the answers already and draws them
 * straight away.
 *
 * <h2>The one line that is about this session rather than about this computer</h2>
 *
 * <p>Everything above is a fact about the install and is the same tomorrow. One
 * line is not: an engine that left threads running when a comparison drove it is
 * driven once and no more until Fiji is restarted, and somebody who is about to
 * press Compare needs to know that before they wonder why a row is missing.
 * {@link #driveNoteOf} is that line, and it says nothing at all when there is
 * nothing to say - which, on every engine measured so far, is the case.
 *
 * <h2>Nothing here runs by itself</h2>
 *
 * <p>Checking looks: it asks which menu commands are registered, which files are
 * on disk, and which classes load. It opens no connection and writes nothing. A
 * file is fetched when somebody presses a button, and at no other moment - no
 * measurement reaches this code, and no macro line can.
 */
public final class EnginePanel {

    /** The section heading. */
    public static final String HEADING = "Engines";

    /** The left column's title. */
    public static final String NAME_HEADING = "Engine";

    /** The right column's title. */
    public static final String STATUS_HEADING = "Status";

    /** The promise that a measurement needs none of this. */
    public static final String NOTHING_RUNS_HERE =
            "Nothing in this section runs while a recording is being measured. Checking which"
                    + " engines are here reads this computer and opens no connection.";

    /** The promise that a person is asked before anything is fetched. */
    public static final String NOTHING_FETCHED_UNASKED =
            "A file is fetched when a button here is pressed, and at no other time. Rows with no"
                    + " button say what to do instead, and a rankings run treats an absent engine"
                    + " as absent rather than fetching it.";

    /** What the button says while a repair is running. */
    public static final String WORKING = "Working...";

    /** The heading of the box that reports what a repair did. */
    public static final String RESULT_TITLE = "Registration & Drift Comparison - Engines";

    /** What a repair adds when Fiji has to be restarted before the engine loads. */
    public static final String RESTART_NEEDED = "Restart Fiji before using this engine.";

    private final AutofixService service;
    private final List<Line> lines = new ArrayList<Line>();

    private DialogForm form;

    /** A panel that reads and repairs through one service. */
    public EnginePanel(AutofixService service) {
        this.service = service == null ? new AutofixService() : service;
    }

    /**
     * Draws the section onto a form.
     *
     * <p>Returns without waiting for anything. When this computer has not been
     * read yet, the rows say they are being checked and a background thread
     * fills them in.
     */
    public void render(DialogForm form) {
        this.form = form;
        form.addHeader(HEADING);
        form.addTableRow(NAME_HEADING, STATUS_HEADING, true);
        boolean known = service.hasLooked();
        for (DependencyServiceCore.DialogRow row
                : known ? service.rows() : service.rowsBeforeLooking()) {
            addLine(form, row);
        }
        form.addSpacer(4);
        form.addNote(NOTHING_RUNS_HERE);
        form.addHelpText(NOTHING_FETCHED_UNASKED);
        if (!known) inBackground(new Runnable() {
            @Override public void run() {
                show(service.rows());
            }
        });
    }

    /** The engine each drawn line is about, in the order they were drawn. */
    public List<EngineId> engines() {
        List<EngineId> drawn = new ArrayList<EngineId>(lines.size());
        for (Line line : lines) drawn.add(line.engine);
        return drawn;
    }

    /** What each drawn line's right-hand column says, in the order drawn. */
    public List<String> statuses() {
        List<String> shown = new ArrayList<String>(lines.size());
        for (Line line : lines) shown.add(line.status.getText());
        return shown;
    }

    // -------------------------------------------------------------- the text

    /** The state word for a row, exactly as the machinery worded it. */
    public static String statusOf(DependencyServiceCore.DialogRow row) {
        return row.getStatusLabel();
    }

    /**
     * The line under a row that needs attention, naming what was not found.
     *
     * <p>Empty for a row that is present: "All checks passed" under an engine
     * already marked as present is noise. The machinery's detail can run to
     * several lines - one per class or file it looked for - and they are joined
     * here so a row stays one line tall.
     */
    public static String detailOf(DependencyServiceCore.DialogRow row) {
        if (row.getStatus() == null) return "";
        if (row.getStatus().isPresent() || row.getStatus().isChecking()) return "";
        return oneLine(row.getStatusDetail());
    }

    /**
     * The sentence shown where a button would be, or empty when there is one.
     *
     * <p>This is the entry's own stated reason rather than the machinery's
     * shorter note, because the reason names the page somebody should visit and
     * that is the whole use of the line.
     */
    public static String reasonOf(DependencyServiceCore.DialogRow row) {
        DependencySpec spec = row.getSpec();
        if (spec.isFixableInApp()) return "";
        if (row.getStatus() == null) return "";
        if (row.getStatus().isPresent() || row.getStatus().isChecking()) return "";
        return spec.getNonFixableReason();
    }

    /**
     * What a comparison would do about this engine right now, or an empty string
     * when there is nothing worth saying.
     *
     * <p>Two things can put a sentence here. An engine that a comparison has
     * already driven in this session, and that left threads running when it did,
     * is not driven again - nothing is stopped and nothing is closed, so
     * restarting Fiji is what it takes to compare it a second time. And an
     * engine nobody has yet driven twice anywhere carries the note saying the
     * question is open rather than answered.
     *
     * <p>Written for an engine that is here, and for no other kind. "This engine
     * is driven once a session" under one that is not installed at all is noise
     * on a row whose whole message is that there is nothing to drive.
     */
    public static String driveNoteOf(DependencyServiceCore.DialogRow row) {
        return driveNoteOf(row, EngineRunner.forThisSession());
    }

    /**
     * The same line, read off a supplied runner.
     *
     * <p>The seam exists so that what this says about an engine that leaked can
     * be asserted without arranging for one to leak inside the test runner's own
     * session.
     */
    static String driveNoteOf(DependencyServiceCore.DialogRow row, EngineRunner runner) {
        if (row.getStatus() == null || !row.getStatus().isPresent()) return "";
        EngineId engine = (EngineId) row.getSpec().getId();
        EngineDescriptor descriptor = EngineDescriptor.forEngine(engine);
        String session = runner.driveOnceNote(engine);
        return session.isEmpty() ? descriptor.driveNote() : session;
    }

    /** The button captions a row offers, in the order the machinery listed them. */
    public static List<String> buttonLabelsOf(DependencyServiceCore.DialogRow row) {
        List<String> labels = new ArrayList<String>();
        for (DependencyServiceCore.DialogAction action : row.getActions()) {
            labels.add(action.getLabel());
        }
        return labels;
    }

    /** What the box says after a repair ran. */
    public static String messageOf(DependencyFixResult result) {
        String message = result.getMessage() == null ? "" : result.getMessage().trim();
        if (!result.isRestartRequired()) return message;
        return message.isEmpty() ? RESTART_NEEDED : message + "\n\n" + RESTART_NEEDED;
    }

    // ------------------------------------------------------------ the drawing

    private void addLine(DialogForm form, DependencyServiceCore.DialogRow row) {
        DependencySpec spec = row.getSpec();
        JPanel rowPanel = form.addTableRow(spec.getDisplayName(), statusOf(row), false);
        JLabel status = rightmostLabel(rowPanel);

        Line line = new Line((EngineId) spec.getId(), status,
                button(), form.addHelpText(""), form.addHelpText(""));
        rowPanel.add(line.button);
        lines.add(line);
        line.show(row);
    }

    private JButton button() {
        final JButton button = new JButton("");
        button.setFocusPainted(false);
        button.setMaximumSize(new Dimension(260, 22));
        button.setVisible(false);
        button.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent event) {
                press(button);
            }
        });
        return button;
    }

    /** Puts a fresh set of answers on the screen. Runs on the window's thread. */
    private void show(final List<DependencyServiceCore.DialogRow> rows) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() {
                for (DependencyServiceCore.DialogRow row : rows) {
                    for (Line line : lines) {
                        if (line.engine == row.getSpec().getId()) line.show(row);
                    }
                }
                if (form != null) form.repack();
            }
        });
    }

    /**
     * Runs a row's button: the repair on another thread, the report back on the
     * window's own. Both the repair and the reading that follows it are slow
     * enough to freeze a window, so neither happens on it.
     */
    private void press(final JButton button) {
        Line pressed = lineOf(button);
        if (pressed == null || pressed.action == null) return;
        final EngineId engine = pressed.engine;
        final String actionId = pressed.action.getActionId();
        final String caption = button.getText();
        button.setEnabled(false);
        button.setText(WORKING);
        inBackground(new Runnable() {
            @Override public void run() {
                DependencyFixResult result;
                try {
                    result = service.fix(engine, actionId);
                } catch (RuntimeException failed) {
                    result = new DependencyFixResult(engine, true, false, false,
                            failed.getClass().getSimpleName() + ": " + failed.getMessage());
                }
                final DependencyFixResult finished = result;
                final List<DependencyServiceCore.DialogRow> rows = service.rows();
                SwingUtilities.invokeLater(new Runnable() {
                    @Override public void run() {
                        button.setText(caption);
                        button.setEnabled(true);
                    }
                });
                show(rows);
                report(button, finished);
            }
        });
    }

    private static void report(final JButton beside, final DependencyFixResult result) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() {
                JOptionPane.showMessageDialog(beside,
                        DialogForm.wrapped(messageOf(result).replace("\n", "<br>")),
                        RESULT_TITLE, JOptionPane.INFORMATION_MESSAGE);
            }
        });
    }

    private static void inBackground(Runnable work) {
        Thread worker = new Thread(work, "RegDrift engines");
        worker.setDaemon(true);
        worker.start();
    }

    private Line lineOf(JButton button) {
        for (Line line : lines) {
            if (line.button == button) return line;
        }
        return null;
    }

    /** The status label of a two-column row, which is the one added last. */
    private static JLabel rightmostLabel(JPanel rowPanel) {
        JLabel found = null;
        for (int i = 0; i < rowPanel.getComponentCount(); i++) {
            if (rowPanel.getComponent(i) instanceof JLabel) {
                found = (JLabel) rowPanel.getComponent(i);
            }
        }
        return found == null ? new JLabel("") : found;
    }

    private static String oneLine(String text) {
        if (text == null) return "";
        String joined = text.replace("\r", "").replace("\n", "; ").trim();
        while (joined.contains("; ;")) joined = joined.replace("; ;", ";");
        return joined;
    }

    /** One drawn line, and the widgets an answer has to fill in. */
    private static final class Line {

        private final EngineId engine;
        private final JLabel status;
        private final JButton button;
        private final JLabel detail;
        private final JLabel reason;

        private DependencyServiceCore.DialogAction action;

        Line(EngineId engine, JLabel status, JButton button, JLabel detail, JLabel reason) {
            this.engine = engine;
            this.status = status;
            this.button = button;
            this.detail = detail;
            this.reason = reason;
        }

        /** Writes one row's finished text into the widgets already on the form. */
        void show(DependencyServiceCore.DialogRow row) {
            status.setText(statusOf(row));
            write(detail, detailOf(row));
            write(reason, both(reasonOf(row), driveNoteOf(row)));
            action = row.getActions().isEmpty() ? null : row.getActions().get(0);
            button.setText(action == null ? "" : action.getLabel());
            button.setVisible(action != null);
        }

        /** Two sentences on one line, or whichever of them there is. */
        private static String both(String first, String second) {
            if (first.isEmpty()) return second;
            if (second.isEmpty()) return first;
            return first + " " + second;
        }

        private static void write(JLabel label, String text) {
            label.setText(DialogForm.wrapped(text));
            label.setVisible(!text.isEmpty());
        }
    }
}
