/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.autofix;

import sc.fiji.autofix.core.Artifacts;
import sc.fiji.autofix.core.DependencyFixPlan;
import sc.fiji.autofix.core.DependencyFixResult;
import sc.fiji.autofix.core.DependencyFixer;
import sc.fiji.autofix.core.DependencyKey;
import sc.fiji.autofix.core.DependencyServiceCore;
import sc.fiji.autofix.core.DependencySpec;
import sc.fiji.autofix.core.DependencyStatus;
import sc.fiji.autofix.core.JarDependencyFixer;
import sc.fiji.autofix.core.Product;
import sc.fiji.autofix.core.SpecCatalogue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * What this plugin asks about registration engines, and the single place an
 * install can start from.
 *
 * <p>Two operations, and the gap between them is the point of the class:
 *
 * <ul>
 *   <li><b>Looking</b> - {@link #rows()}, {@link #status}, {@link #presentEngines()} - reads the
 *       classes already loaded and the files already on disk. It reaches no
 *       network, writes nothing, and runs whenever a window is opened.</li>
 *   <li><b>Repairing</b> - {@link #fix} - fetches files and puts them in Fiji's
 *       folders. It runs when somebody presses a button and at no other time.
 *       No measurement, no setting and no macro line reaches it.</li>
 * </ul>
 *
 * <p>That is the whole of house rule 9: a diagnosis on a bare Fiji works
 * completely, and a person who never presses anything never downloads anything.
 *
 * <h2>The name written into somebody else's Fiji</h2>
 *
 * <p>{@link #PRODUCT} is set once, here, before anything can be written. When a
 * repair has to rename a file the running Java process still holds open - the
 * ordinary case on Windows - the rename is handed to a helper that waits for
 * Fiji to close, and that helper, its log, and the temporary file used to test
 * whether a folder is writable are all named after this product. Those names are
 * the trace somebody follows when a repair did not happen, so they name the
 * plugin that caused them and not the shared machinery underneath.
 *
 * <p>The name matches the folder the auto-save tree writes into, so everything
 * this plugin leaves on a disk carries one word.
 */
public final class AutofixService {

    /** The name on every file this plugin writes into somebody's Fiji. */
    public static final Product PRODUCT = Product.named("RegistrationDriftComparison");

    /*
     * Registered before any repair can run, because the deferred-rename helper
     * is a stateless singleton inside the shared machinery and takes its name
     * from here. Safe as a one-time registration precisely because that
     * machinery is compiled into this jar under this plugin's own namespace: a
     * sibling plugin in the same Fiji holds an unrelated copy with an unrelated
     * name.
     */
    static {
        Artifacts.setProduct(PRODUCT);
    }

    /**
     * TurboReg goes first. StackReg and MultiStackReg both call it while they
     * run, so a repair that installed StackReg first would verify itself against
     * a half-built state and report a failure that running it again would fix.
     */
    private static final int ORDER_TURBOREG = 10;
    private static final int ORDER_STACKREG = 20;
    private static final int ORDER_FAST_4D_REG = 30;
    private static final int ORDER_NANOJ_CORE = 40;

    private static AutofixService shared;

    private final SpecCatalogue catalogue;
    private final Map<DependencyKey, DependencyFixer> fixers;
    private final DependencyServiceCore core;
    private final AtomicBoolean looked = new AtomicBoolean(false);

    private DependencyServiceCore beforeLooking;

    /** The catalogue in {@link EngineRegistry}, probed against this Fiji. */
    public AutofixService() {
        this(EngineRegistry.catalogue(), defaultFixers(), null);
    }

    /**
     * A service over a supplied catalogue, repair set and probing step.
     *
     * @param catalogue what to ask about
     * @param fixers    how to repair each entry, keyed by engine
     * @param statuses  substitutes probing entirely, or null to probe for real.
     *                  The seam exists so that dialog and plan behavior can be
     *                  driven from a test without a Fiji install to probe.
     */
    public AutofixService(SpecCatalogue catalogue,
                          Map<DependencyKey, DependencyFixer> fixers,
                          DependencyServiceCore.StatusSnapshotProvider statuses) {
        this.catalogue = catalogue;
        this.fixers = fixers;
        final DependencyServiceCore.StatusSnapshotProvider answers =
                statuses == null ? PROBE_THIS_COMPUTER : statuses;
        this.core = new DependencyServiceCore(catalogue, fixers,
                new DependencyServiceCore.StatusSnapshotProvider() {
                    @Override
                    public Map<DependencyKey, DependencyStatus> snapshot(
                            List<DependencySpec> specs) {
                        Map<DependencyKey, DependencyStatus> found = answers.snapshot(specs);
                        looked.set(true);
                        return found;
                    }
                });
    }

    /** The real reading of this computer, which is what a null seam means. */
    private static final DependencyServiceCore.StatusSnapshotProvider PROBE_THIS_COMPUTER =
            new DependencyServiceCore.StatusSnapshotProvider() {
                @Override
                public Map<DependencyKey, DependencyStatus> snapshot(List<DependencySpec> specs) {
                    return DependencyServiceCore.snapshotStatuses(specs);
                }
            };

    /**
     * The service the dialogs use, built once.
     *
     * <p>Shared because probing is not free - a class lookup walks the class
     * path and a file check walks Fiji.app - and because a window that rebuilt
     * itself would otherwise re-run every probe on every redraw. The answers are
     * cached inside it until {@link #refresh()} or a repair clears them.
     */
    public static synchronized AutofixService forThisFiji() {
        if (shared == null) shared = new AutofixService();
        return shared;
    }

    /**
     * One finished row per engine: name, state, what it would cost, and either a
     * button caption or the sentence explaining why there is no button.
     *
     * <p>Every word in a row is computed by the shared machinery, so this plugin
     * and its two siblings say the same thing about the same situation while
     * looking nothing alike. Probes run on the first call and the answers are
     * kept until something could have changed them.
     */
    public List<DependencyServiceCore.DialogRow> rows() {
        return core.getDialogRows();
    }

    /**
     * True once this computer has actually been read, so a caller knows whether
     * {@link #rows()} would answer from memory or go and look.
     */
    public boolean hasLooked() {
        return looked.get();
    }

    /**
     * The same rows, for a catalogue nothing has looked at yet: every engine
     * reads as being checked, and every row offers the machinery's own way of
     * asking again.
     *
     * <p>This exists because looking is not as cheap as it ought to be. Reading
     * every engine on a full Fiji install measured about five seconds, almost
     * all of it inside the shared machinery's own file checks, and a window that
     * did that before appearing would be a window that appeared five seconds
     * after somebody chose the menu item. So the section is drawn from these
     * rows straight away and filled in when the reading finishes.
     *
     * <p>Nothing is invented here: the words are the machinery's, and "being
     * checked" is a state it already models and already knows how to word.
     */
    public synchronized List<DependencyServiceCore.DialogRow> rowsBeforeLooking() {
        if (beforeLooking == null) {
            beforeLooking = new DependencyServiceCore(catalogue, fixers,
                    new DependencyServiceCore.StatusSnapshotProvider() {
                        @Override
                        public Map<DependencyKey, DependencyStatus> snapshot(
                                List<DependencySpec> specs) {
                            Map<DependencyKey, DependencyStatus> waiting =
                                    new LinkedHashMap<DependencyKey, DependencyStatus>();
                            for (DependencySpec spec : specs) {
                                waiting.put(spec.getId(), DependencyStatus.of(spec.getId(),
                                        DependencyStatus.State.CHECKING, "Not yet checked."));
                            }
                            return waiting;
                        }
                    });
        }
        return beforeLooking.getDialogRows();
    }

    /** Everything the catalogue holds, in the order a panel shows it. */
    public List<DependencySpec> specs() {
        return core.getAllDependencies();
    }

    /** What the last probe found for one engine. Never null. */
    public DependencyStatus status(EngineId engine) {
        return core.getStatusOrChecking(engine);
    }

    /** True when this engine is on this computer and loadable right now. */
    public boolean isPresent(EngineId engine) {
        return core.isAvailable(engine);
    }

    /**
     * The engines present in this Fiji, in catalogue order.
     *
     * <p>What the {@code engines=installed} setting resolves to, and the reason
     * that setting can be honored without anything being fetched.
     */
    public List<EngineId> presentEngines() {
        List<EngineId> present = new ArrayList<EngineId>();
        for (DependencySpec spec : core.getAllDependencies()) {
            EngineId engine = (EngineId) spec.getId();
            if (isPresent(engine)) present.add(engine);
        }
        return Collections.unmodifiableList(present);
    }

    /** Probes everything again, discarding what was cached. */
    public void refresh() {
        core.refreshStatuses();
    }

    /** What repairing everything repairable would fetch, and what it would not. */
    public DependencyFixPlan plan() {
        return core.planFixAll();
    }

    /**
     * Runs one row's button.
     *
     * <p>The single method in this plugin that can reach the network, and it
     * runs when a person presses something. It blocks for as long as the
     * download takes, so a caller on the window's own thread must hand it to
     * another one.
     *
     * @param engine   which row was pressed
     * @param actionId the button's action, from the row itself
     * @return what happened, including whether Fiji has to restart
     */
    public DependencyFixResult fix(EngineId engine, String actionId) {
        return core.runDialogAction(engine, actionId);
    }

    /** The repair paths this plugin offers, keyed by engine. */
    public static Map<DependencyKey, DependencyFixer> defaultFixers() {
        Map<DependencyKey, DependencyFixer> fixers =
                new LinkedHashMap<DependencyKey, DependencyFixer>();
        fixers.put(EngineId.TURBOREG, jarFixer(ORDER_TURBOREG, "TurboReg"));
        fixers.put(EngineId.STACKREG, jarFixer(ORDER_STACKREG, "StackReg"));
        fixers.put(EngineId.FAST_4D_REG, jarFixer(ORDER_FAST_4D_REG, "Fast4DReg"));
        fixers.put(EngineId.NANOJ_CORE, jarFixer(ORDER_NANOJ_CORE, "NanoJ-Core"));
        return fixers;
    }

    private static DependencyFixer jarFixer(int order, String engine) {
        return new JarDependencyFixer(PRODUCT, order,
                engine + " is in place.",
                engine + " install finished.");
    }
}
