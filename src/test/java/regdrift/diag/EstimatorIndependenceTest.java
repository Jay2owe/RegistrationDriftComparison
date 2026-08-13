/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.diag;

import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * T5. The claim the whole plugin rests on, asserted against compiled bytecode.
 *
 * <h2>The claim</h2>
 *
 * <p>This plugin recommends registration methods, and its author wrote one. The recommendation is
 * only worth reading if the measurement behind it does not run on that author's own registration
 * criterion - not "does not favour it", <em>does not contain it</em>. So the measurement package must
 * reference nothing from the log-ratio criterion, and the two estimators inside it must share nothing
 * with each other beyond the interface they both satisfy.
 *
 * <p>The second half matters as much as the first. Two estimators that shared an alignment engine, a
 * pyramid schedule or a convergence rule would agree with each other for reasons that have nothing to
 * do with the recording, and the agreement column - the plugin's confidence signal - would be
 * measuring the code rather than the data.
 *
 * <h2>Why bytecode, and why by package</h2>
 *
 * <p>An import is not the only route to a class, and a search through the source would pass a fully
 * written {@code new logratio.core.PairAligner()}. The constant pool holds both spellings as the same
 * entry.
 *
 * <p>The measurement package is asserted <b>by name</b>, so a class added to it in stage 08 or 09 is
 * covered the day it is written, without anybody having to remember to come back here - which is the
 * whole point, because the person who would have to remember is the person adding the reference.
 *
 * <h2>This test has been seen to fail</h2>
 *
 * <p>A structural test that has never failed proves nothing about what it would catch. This one was
 * run against a deliberately broken tree, with a stub {@code logratio.core.PairAligner} added to this
 * repository and imported by {@link PyramidSsd}, and it failed in three places at once; the details
 * are in the commit that introduced it. The stub was then removed.
 */
public class EstimatorIndependenceTest {

    /** The packages that carry the measurement. Asserted by name, never as a list of classes. */
    private static final String[] MEASUREMENT_PACKAGES = {"regdrift.diag", "regdrift.internal"};

    /**
     * The log-ratio criterion, in every spelling that would reach it.
     *
     * <p>These are the classes that <em>are</em> the author's own registration method: the aligner,
     * the whole-recording driver, the robust norm that makes it robust, the log plane it works on,
     * the multi-lag reconciler and the chain repair. Nothing in the measurement may name any of them.
     */
    private static final String[] THE_LOG_RATIO_CRITERION = {
            "logratio/", "logratio.",
            "PairAligner", "RobustNorm", "LogPlane", "Reconciler", "ChainRepair",
    };

    /**
     * What the two estimators are allowed to have in common: the interface they both satisfy, the
     * label that says what size a pixel is, and the language itself.
     *
     * <p>Kept short on purpose. Every entry added here is a claim that the thing is not machinery,
     * and the only way that claim stays true is if adding to this list is uncomfortable.
     */
    private static final String[] THE_COMMON_PATH = {
            // The one path both are called through, and the value type they both answer in.
            "regdrift.diag.Estimator",
            "regdrift.diag.Estimator$Displacement",
            "regdrift.diag.Estimator$Status",
            // The effective pixel size. A label of a few words and a factor - see defect D12.
            "regdrift.diag.Frames",
            "regdrift.diag.Frames$Bin",
    };

    /** The language. Arithmetic, text and array copying, shared by every class ever compiled. */
    private static final String[] THE_LANGUAGE = {
            "java.lang.Object", "java.lang.String", "java.lang.StringBuilder",
            "java.lang.Math", "java.lang.Float", "java.lang.Double", "java.lang.System",
            "java.lang.IllegalArgumentException", "java.util.Arrays",
    };

    // ------------------------------------------------------------ the canary

    /**
     * The scan has to be able to see a reference that is really there, or every assertion below is
     * decoration.
     *
     * <p>{@link Frames} really does use {@code ij.ImageStack}, and it is in one of the packages
     * asserted here. If the pool reader can find that one, it can find one that should not be there.
     */
    @Test
    public void theScanSeesReferencesThatAreReallyThere() throws IOException {
        Bytecode.Pool frames = Bytecode.poolOf("regdrift.diag.Frames");
        assertTrue("the scan must see a class this package really uses",
                frames.mentions("ij/ImageStack"));
        assertTrue("and must see it as a named class, not only as text",
                frames.dottedClasses().contains("ij.ImageStack"));
        assertFalse("and must not invent one that is absent",
                frames.dottedClasses().contains("logratio.core.PairAligner"));

        Set<String> ssd = referencedClassesOf("regdrift.diag.PyramidSsd");
        assertTrue("the estimator scan must see the interface it really implements: " + ssd,
                ssd.contains("regdrift.diag.Estimator"));
    }

    /** And it has to be looking at something. A renamed package would otherwise assert nothing. */
    @Test
    public void everyAssertedPackageHoldsCompiledClasses() {
        for (String packageName : MEASUREMENT_PACKAGES) {
            List<String> classes = Bytecode.classesIn(packageName);
            assertTrue("package " + packageName + " is asserted by name here and compiled to no"
                    + " class at all, so scanning it asserts nothing", classes.size() >= 3);
        }
        assertTrue(Bytecode.classesIn("regdrift.diag").contains("regdrift.diag.PhaseCorrelation"));
        assertTrue(Bytecode.classesIn("regdrift.diag").contains("regdrift.diag.PyramidSsd"));
    }

    // ------------------------------------------------------- the two assertions

    /** Nothing in the measurement names anything from the log-ratio criterion. */
    @Test
    public void theMeasurementSharesNothingWithTheLogRatioCriterion() throws IOException {
        for (String packageName : MEASUREMENT_PACKAGES) {
            for (String className : Bytecode.classesIn(packageName)) {
                Bytecode.Pool pool = Bytecode.poolOf(className);
                for (String banned : THE_LOG_RATIO_CRITERION) {
                    assertFalse(className + " names " + banned + ". The measurement runs on"
                                    + " published estimators that share nothing with this plugin's"
                                    + " author's own registration method - that independence is the"
                                    + " reason the recommendation is worth reading, and this is"
                                    + " where it is kept true.",
                            pool.mentions(banned));
                }
            }
        }
    }

    /**
     * The two estimators share the interface, the scale label and the language, and nothing else.
     *
     * <p>Not "no obvious machinery" - nothing. The failure message lists whatever turned up, because
     * the useful thing to know when this breaks is exactly which class crept into both.
     *
     * <p>Each estimator's own name and its companions' are taken out of its own set first, since a
     * class naming itself is not sharing anything. That leaves one case this cannot see - one
     * estimator reaching directly into the other - and {@link #neitherEstimatorNamesTheOther} is
     * where that is caught.
     */
    @Test
    public void theTwoEstimatorsShareNoClassBeyondTheirCommonPath() throws IOException {
        Set<String> phase = referencedClassesOf("regdrift.diag.PhaseCorrelation");
        phase.removeAll(ownNames("regdrift.diag.PhaseCorrelation"));
        Set<String> ssd = referencedClassesOf("regdrift.diag.PyramidSsd");
        ssd.removeAll(ownNames("regdrift.diag.PyramidSsd"));

        Set<String> shared = new TreeSet<String>(phase);
        shared.retainAll(ssd);
        shared.removeAll(Arrays.asList(THE_COMMON_PATH));
        shared.removeAll(Arrays.asList(THE_LANGUAGE));

        assertTrue("the two estimators are supposed to be independent implementations and both"
                + " name " + shared + ". If that is genuinely not machinery, add it to"
                + " THE_COMMON_PATH with the reason; if it is, the confidence signal these two"
                + " produce is measuring the code rather than the recording.", shared.isEmpty());
    }

    /** And neither of them names the other, which no allow-list could excuse. */
    @Test
    public void neitherEstimatorNamesTheOther() throws IOException {
        for (String className : Bytecode.classAndNested("regdrift.diag.PhaseCorrelation")) {
            assertFalse(className + " names the other estimator",
                    Bytecode.poolOf(className).mentions("PyramidSsd"));
        }
        for (String className : Bytecode.classAndNested("regdrift.diag.PyramidSsd")) {
            assertFalse(className + " names the other estimator",
                    Bytecode.poolOf(className).mentions("PhaseCorrelation"));
        }
    }

    /**
     * The class that runs both is allowed to name both, and is not allowed to be where they meet.
     *
     * <p>{@link Estimators} calls each of them and puts their two answers side by side. It must not
     * become a place where one estimator's working is handed to the other - a shared pyramid, a
     * shared starting position, a shared convergence decision - because that is the same coupling by
     * another route, and it would not show up in the two assertions above.
     */
    @Test
    public void theClassThatRunsBothPassesNothingFromOneToTheOther() {
        java.lang.reflect.Method[] methods = Estimators.class.getDeclaredMethods();
        for (int i = 0; i < methods.length; i++) {
            Class<?>[] parameters = methods[i].getParameterTypes();
            for (int p = 0; p < parameters.length; p++) {
                assertFalse("Estimators." + methods[i].getName() + " takes a "
                                + parameters[p].getName() + ". Neither estimator's working may be"
                                + " passed through this class into the other one.",
                        parameters[p] == PhaseCorrelation.class
                                || parameters[p] == PyramidSsd.class);
            }
        }
    }

    // ------------------------------------------------------------- machinery

    /** Every class a class and its compiler-generated companions name, in dotted form. */
    private static Set<String> referencedClassesOf(String className) throws IOException {
        Set<String> named = new TreeSet<String>();
        for (String each : Bytecode.classAndNested(className)) {
            named.addAll(Bytecode.poolOf(each).dottedClasses());
        }
        return named;
    }

    /** A class's own name and its companions', which are not "shared" in any interesting sense. */
    private static Set<String> ownNames(String className) {
        return new TreeSet<String>(Bytecode.classAndNested(className));
    }
}
