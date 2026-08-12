/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.ui;

import sc.fiji.oc3d.core.ui.CollapsiblePane;
import sc.fiji.oc3d.core.ui.ToggleSwitch;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The Swing furniture every dialog in this plugin is built from: section
 * headers, labelled rows, toggle switches, dropdowns, text and folder fields, a
 * disclosure that hides the settings most people never touch, and a modal shell
 * with OK and Cancel.
 *
 * <p>Adapted from CPC's {@code ui/CPCDialog}, which is the aesthetic this family
 * shares. Two things are different here and both are deliberate:
 *
 * <ul>
 *   <li><b>The widgets are handed back, not counted.</b> CPC reads its values
 *       through {@code getNextChoice()} in the order the controls were added,
 *       which mirrors {@code GenericDialog}. Inserting a control in the middle
 *       of that sequence silently shifts every value after it into the wrong
 *       variable. Here each {@code add} call returns its widget and the caller
 *       keeps the reference, so a control can be added anywhere without moving
 *       anything else.</li>
 *   <li><b>The window is built when it is shown, not when the form is.</b>
 *       Constructing a {@code JDialog} needs a screen; building a panel does
 *       not. Keeping the two apart means the whole content of a dialog - its
 *       defaults, its wording, what its controls read back as - can be built and
 *       checked by a test on a machine with no display at all, which is the
 *       difference between the dialogs being tested and being looked at.</li>
 * </ul>
 *
 * <h2>Why not {@code GenericDialog}</h2>
 *
 * <p>ImageJ's {@code GenericDialog} lays its own controls out and reads them
 * back in the order they were added, and a section that folds away is not
 * something it has a place for. The disclosure below is the shared
 * {@code CollapsiblePane} widget, which is a Swing panel with a vertical box
 * layout, and it drops into this form's own vertical column with nothing in
 * between. That is the CPC route, and it is the one taken.
 */
public class DialogForm {

    private static final Color BG_COLOR = new Color(245, 245, 245);
    private static final Color HEADER_COLOR = new Color(55, 71, 79);
    private static final Color LABEL_COLOR = new Color(33, 33, 33);
    private static final Color HELP_COLOR = new Color(117, 117, 117);
    private static final Color NOTE_COLOR = new Color(84, 110, 122);

    private final String title;
    private final JPanel content;
    private final List<String> headers = new ArrayList<String>();

    private JComponent target;
    private Check check;
    private JDialog shell;
    private boolean accepted;

    /** A form with a window title, ready to have sections added to it. */
    public DialogForm(String title) {
        this.title = title == null ? "" : title;
        this.content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(new EmptyBorder(15, 20, 10, 20));
        content.setBackground(BG_COLOR);
        this.target = content;
    }

    /** What is asked before the dialog is allowed to close on OK. */
    public interface Check {

        /**
         * @return a sentence describing what has to change before this dialog
         *         can be accepted, or null when it can
         */
        String problem();
    }

    /** The window title. */
    public String title() {
        return title;
    }

    /** The section headers, in the order they were added. */
    public List<String> headers() {
        return new ArrayList<String>(headers);
    }

    /** The panel every control was added to. */
    public JPanel content() {
        return content;
    }

    /** Sets what is asked before OK is allowed to close the dialog. */
    public void setCheck(Check check) {
        this.check = check;
    }

    // ------------------------------------------------------------- sections

    /** A bold section title with a rule under it. */
    public void addHeader(String text) {
        headers.add(text);
        target.add(Box.createVerticalStrut(10));
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 13f));
        label.setForeground(HEADER_COLOR);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        target.add(label);
        target.add(Box.createVerticalStrut(4));

        JSeparator rule = new JSeparator(SwingConstants.HORIZONTAL);
        rule.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        rule.setAlignmentX(Component.LEFT_ALIGNMENT);
        target.add(rule);
        target.add(Box.createVerticalStrut(6));
    }

    /**
     * Starts a group of controls that can be shown and hidden together.
     *
     * <p>Everything added between this call and {@link #endGroup()} goes into
     * the returned panel, so a control that belongs to one mode can be taken off
     * the screen when another mode is chosen.
     */
    public JPanel beginGroup() {
        JPanel group = new JPanel();
        group.setLayout(new BoxLayout(group, BoxLayout.Y_AXIS));
        group.setAlignmentX(Component.LEFT_ALIGNMENT);
        group.setOpaque(false);
        target = group;
        return group;
    }

    /** Closes the current group and puts it on the form. */
    public void endGroup() {
        if (target != content) {
            content.add(target);
            target = content;
        }
    }

    /**
     * Starts a disclosure: a header row that folds its contents away.
     *
     * <p>Everything added between this call and {@link #endCollapsible(CollapsiblePane)} goes
     * inside it. The pane is the shared widget from the relocated core, so the
     * triangle, the keyboard focus and the height behavior are the same here as
     * in every sibling plugin.
     */
    public CollapsiblePane beginCollapsible(String title, boolean startExpanded) {
        CollapsiblePane pane = new CollapsiblePane(title, startExpanded);
        target = pane.body();
        return pane;
    }

    /** Closes the current disclosure and puts it on the form. */
    public void endCollapsible(CollapsiblePane pane) {
        target = content;
        pane.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(pane);
        content.add(Box.createVerticalStrut(4));
    }

    // ------------------------------------------------------------- controls

    /** A labelled toggle switch. */
    public ToggleSwitch addToggle(String label, boolean initialState) {
        ToggleSwitch toggle = new ToggleSwitch(initialState);
        JPanel row = row();
        row.add(label(label));
        row.add(Box.createHorizontalGlue());
        row.add(toggle);
        target.add(row);
        target.add(Box.createVerticalStrut(4));
        return toggle;
    }

    /** A labelled dropdown. */
    public JComboBox<String> addChoice(String label, String[] items, String selected) {
        JComboBox<String> combo = new JComboBox<String>(items);
        if (selected != null) combo.setSelectedItem(selected);
        combo.setMaximumSize(new Dimension(300, 24));
        JPanel row = row();
        row.add(label(label));
        row.add(Box.createHorizontalGlue());
        row.add(combo);
        target.add(row);
        target.add(Box.createVerticalStrut(4));
        return combo;
    }

    /** A labelled text field. */
    public JTextField addStringField(String label, String value, int columns) {
        JTextField field = new JTextField(value == null ? "" : value, columns);
        field.setMaximumSize(new Dimension(columns * 12, 24));
        JPanel row = row();
        row.add(label(label));
        row.add(Box.createHorizontalGlue());
        row.add(field);
        target.add(row);
        target.add(Box.createVerticalStrut(4));
        return field;
    }

    /** A folder field with a Browse button beside it. */
    public JTextField addFolderField(String label, String value) {
        final JTextField field = new JTextField(value == null ? "" : value, 18);
        field.setMaximumSize(new Dimension(220, 24));
        JPanel row = row();
        row.add(label(label));
        row.add(Box.createHorizontalGlue());
        row.add(field);
        row.add(Box.createHorizontalStrut(4));

        JButton browse = new JButton("...");
        browse.setPreferredSize(new Dimension(28, 24));
        browse.setMaximumSize(new Dimension(28, 24));
        browse.setFocusPainted(false);
        browse.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent event) {
                JFileChooser chooser = new JFileChooser();
                chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                String current = field.getText().trim();
                if (!current.isEmpty()) chooser.setCurrentDirectory(new File(current));
                if (chooser.showOpenDialog(shell) == JFileChooser.APPROVE_OPTION) {
                    field.setText(chooser.getSelectedFile().getAbsolutePath());
                }
            }
        });
        row.add(browse);
        target.add(row);
        target.add(Box.createVerticalStrut(4));
        return field;
    }

    /**
     * A column of radio buttons, one line each, with exactly one chosen.
     *
     * <p>A dropdown would take less room. It would also hide four of the five
     * things this plugin can do behind a click, and the choice between them is
     * the decision the whole dialog exists to put in front of somebody.
     */
    public Radios addRadios(String[] labels, String selected) {
        Radios radios = new Radios();
        for (String text : labels) {
            JRadioButton button = new JRadioButton(text, text.equals(selected));
            button.setOpaque(false);
            button.setFont(button.getFont().deriveFont(Font.PLAIN, 12f));
            button.setForeground(LABEL_COLOR);
            button.setAlignmentX(Component.LEFT_ALIGNMENT);
            radios.add(button);
            JPanel row = row();
            row.add(button);
            row.add(Box.createHorizontalGlue());
            target.add(row);
        }
        target.add(Box.createVerticalStrut(4));
        return radios;
    }

    /** Small gray text under the control above it. */
    public JLabel addHelpText(String text) {
        JLabel help = new JLabel(wrapped(text));
        help.setFont(help.getFont().deriveFont(Font.ITALIC, 10f));
        help.setForeground(HELP_COLOR);
        help.setAlignmentX(Component.LEFT_ALIGNMENT);
        help.setBorder(new EmptyBorder(0, 24, 2, 0));
        target.add(help);
        target.add(Box.createVerticalStrut(2));
        return help;
    }

    /** A plain sentence on the form. */
    public JLabel addMessage(String text) {
        JLabel message = new JLabel(wrapped(text));
        message.setFont(message.getFont().deriveFont(Font.PLAIN, 11f));
        message.setForeground(LABEL_COLOR);
        message.setAlignmentX(Component.LEFT_ALIGNMENT);
        message.setBorder(new EmptyBorder(0, 4, 2, 0));
        target.add(message);
        target.add(Box.createVerticalStrut(4));
        return message;
    }

    /** A sentence set apart, for something the reader has to take account of. */
    public JLabel addNote(String text) {
        JLabel note = addMessage(text);
        note.setForeground(NOTE_COLOR);
        note.setFont(note.getFont().deriveFont(Font.ITALIC, 11f));
        return note;
    }

    /** A row of two fixed-width columns, for a list rendered as a table. */
    public JPanel addTableRow(String left, String right, boolean heading) {
        JPanel row = row();
        JLabel leftLabel = new JLabel(left);
        leftLabel.setPreferredSize(new Dimension(220, 18));
        leftLabel.setMaximumSize(new Dimension(220, 18));
        JLabel rightLabel = new JLabel(right);
        Font font = leftLabel.getFont().deriveFont(heading ? Font.BOLD : Font.PLAIN, 11f);
        leftLabel.setFont(font);
        rightLabel.setFont(font);
        leftLabel.setForeground(heading ? HEADER_COLOR : LABEL_COLOR);
        rightLabel.setForeground(heading ? HEADER_COLOR : LABEL_COLOR);
        row.add(leftLabel);
        row.add(Box.createHorizontalStrut(8));
        row.add(rightLabel);
        row.add(Box.createHorizontalGlue());
        target.add(row);
        target.add(Box.createVerticalStrut(2));
        return row;
    }

    /** Vertical space. */
    public void addSpacer(int height) {
        target.add(Box.createVerticalStrut(height));
    }

    // -------------------------------------------------------------- showing

    /**
     * Puts the dialog on the screen and waits for it.
     *
     * <p>This is the single method here that needs a display. Everything above
     * builds panels, which is why the content of a dialog can be checked without
     * one.
     *
     * @return true when OK was pressed, false when it was cancelled or closed
     */
    public boolean showModal() {
        accepted = false;
        shell = new JDialog((Frame) null, title, true);
        shell.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(BG_COLOR);
        shell.getContentPane().setLayout(new BorderLayout());
        shell.getContentPane().add(scroll, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 8));
        buttons.setBackground(BG_COLOR);
        JButton cancel = new JButton("Cancel");
        JButton ok = new JButton("OK");
        cancel.setPreferredSize(new Dimension(80, 28));
        ok.setPreferredSize(new Dimension(80, 28));
        cancel.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent event) {
                accepted = false;
                shell.dispose();
            }
        });
        ok.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent event) {
                String problem = check == null ? null : check.problem();
                if (problem != null) {
                    JOptionPane.showMessageDialog(shell, wrapped(problem), title,
                            JOptionPane.WARNING_MESSAGE);
                    return;
                }
                accepted = true;
                shell.dispose();
            }
        });
        buttons.add(cancel);
        buttons.add(ok);
        shell.getContentPane().add(buttons, BorderLayout.SOUTH);
        shell.getRootPane().setDefaultButton(ok);

        repack();
        shell.setVisible(true);
        return accepted;
    }

    /**
     * Lays the dialog out again after a group was shown or hidden.
     *
     * <p>Does nothing before the dialog is on the screen, so a group can be
     * hidden while the form is still being built.
     */
    public void repack() {
        if (shell == null) return;
        shell.pack();
        Dimension preferred = shell.getPreferredSize();
        int tallest = (int) (Toolkit.getDefaultToolkit().getScreenSize().height * 0.8);
        if (preferred.height > tallest) {
            shell.setSize(preferred.width + 30, tallest);
        }
        shell.setLocationRelativeTo(null);
    }

    /** A set of radio buttons of which exactly one is chosen. */
    public static final class Radios {

        private final ButtonGroup group = new ButtonGroup();
        private final List<JRadioButton> buttons = new ArrayList<JRadioButton>();

        void add(JRadioButton button) {
            group.add(button);
            buttons.add(button);
        }

        /** The label of the chosen button, or the first label when none is. */
        public String selected() {
            for (JRadioButton button : buttons) {
                if (button.isSelected()) return button.getText();
            }
            return buttons.isEmpty() ? "" : buttons.get(0).getText();
        }

        /** Chooses the button carrying this label. Unknown labels change nothing. */
        public void select(String label) {
            for (JRadioButton button : buttons) {
                if (button.getText().equals(label)) {
                    button.setSelected(true);
                    return;
                }
            }
        }

        /** Every label, in the order they were added. */
        public List<String> labels() {
            List<String> texts = new ArrayList<String>(buttons.size());
            for (JRadioButton button : buttons) texts.add(button.getText());
            return texts;
        }

        /** Runs whenever the chosen button changes. */
        public void onChange(final Runnable listener) {
            ActionListener action = new ActionListener() {
                @Override public void actionPerformed(ActionEvent event) {
                    listener.run();
                }
            };
            for (JRadioButton button : buttons) button.addActionListener(action);
        }
    }

    // ------------------------------------------------------------- internal

    private JLabel label(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 12f));
        label.setForeground(LABEL_COLOR);
        return label;
    }

    private JPanel row() {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        row.setBorder(new EmptyBorder(0, 4, 0, 4));
        return row;
    }

    /**
     * Wraps a sentence so a label breaks it into lines.
     *
     * <p>Public because a label whose text is replaced after the form was built -
     * the line describing the chosen recording, say - has to be given its text
     * in the same shape it was created with, or it stops wrapping.
     */
    public static String wrapped(String text) {
        return "<html><body style='width:320px;'>" + text + "</body></html>";
    }
}
