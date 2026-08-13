/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.advise;

import regdrift.Recommendation;
import regdrift.autofix.AutofixService;
import regdrift.autofix.EngineId;
import sc.fiji.autofix.core.DependencyStatus;

/**
 * What the engine catalogue found on this computer, in the words the
 * recommendation table uses.
 *
 * <p><b>Looking, never fetching.</b> The service behind this reads classes that
 * are already loaded, files that are already on disk and ImageJ's own command
 * table. It opens no connection and writes nothing, which is what lets a
 * recommendation be produced on a Fiji with no registration engine installed at
 * all - house rule 9. The single method in this plugin that can reach a network
 * is the repair a person presses a button for, and nothing here can reach it.
 *
 * <h2>Three answers, not two</h2>
 *
 * <p>"Present" and "absent" are the ordinary ones. The third is
 * {@code unknown}, and it exists because a probe can genuinely fail to find out:
 * a command probe run before ImageJ's command table exists, or a file probe on a
 * process that cannot work out where Fiji.app is, both answer "I could not
 * check". Reporting either of those as {@code no} would send somebody to install
 * something they already have. On a real Fiji - including a bare one, which is
 * the case that matters - the answer is {@code yes} or {@code no}.
 *
 * <p>{@code wrong_version} is not produced by this build. Telling a version this
 * plugin can drive from one it cannot needs the half of the plugin that drives
 * engines, and until that exists a version that would not drive reads as present,
 * because present is what the probe honestly found.
 */
public final class EngineAvailability implements Recommender.Availability {

    private final AutofixService service;

    private EngineAvailability(AutofixService service) {
        this.service = service;
    }

    /**
     * Availability read through one catalogue service.
     *
     * <p>Reads this computer here, once, rather than at the first question. A
     * service that has not looked yet answers "being checked" to everything, and
     * a recommendation table full of {@code unknown} because nobody asked it to
     * look would be a worse answer than the slower one. The answers are cached
     * inside the service, so a second ranking in the same session costs nothing.
     *
     * @param service the service to ask. Reading it fetches nothing
     */
    public static EngineAvailability of(AutofixService service) {
        if (service == null) {
            throw new IllegalArgumentException("engine availability is read through a catalogue"
                    + " service, and none was given");
        }
        service.rows();
        return new EngineAvailability(service);
    }

    @Override
    public Recommendation.Presence presenceOf(EngineId engine) {
        DependencyStatus status = service.status(engine);
        if (status == null) return Recommendation.Presence.UNKNOWN;
        if (status.isPresent()) return Recommendation.Presence.PRESENT;
        if (status.isMissing()) return Recommendation.Presence.ABSENT;
        return Recommendation.Presence.UNKNOWN;
    }
}
