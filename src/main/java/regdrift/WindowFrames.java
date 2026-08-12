/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift;

import java.util.Locale;

/**
 * How many consecutive frames each measurement window holds.
 *
 * <p><b>{@link #AUTO_FRAMES} is twelve, and it is a measurement.</b> Twelve
 * frames per window, three windows, was the one configuration of six that
 * reproduced the whole-recording movement label on all twelve library entries
 * when it was run on 2026-08-12. Windows of eight frames reproduced eleven at
 * their strongest. See {@link Windows} for the other half of the same result.
 *
 * <p>Every pair inside a window is genuinely consecutive, which is the whole
 * point: the descriptors that separate jitter from a random walk are ratios of
 * one step to the next, and a step measured across a gap is not the same
 * quantity. Nothing here is a subsample of the recording that needs a
 * correction factor applied to it afterwards.
 *
 * <p>A window cannot be shorter than two frames, because one frame contains no
 * pair to measure.
 */
public final class WindowFrames {

    /**
     * Frames per window when the option is left at {@code auto}.
     *
     * <p>Twelve. Measured, see the class note.
     */
    public static final int AUTO_FRAMES = 12;

    /** Shortest window that contains a frame pair at all. */
    public static final int MIN_FRAMES = 2;

    /** The macro value {@link #auto()} is written as. */
    public static final String AUTO_VALUE = "auto";

    private static final WindowFrames AUTO = new WindowFrames(true, AUTO_FRAMES);

    private final boolean auto;
    private final int frames;

    private WindowFrames(boolean auto, int frames) {
        this.auto = auto;
        this.frames = frames;
    }

    /** Let the measured default decide, which is {@link #AUTO_FRAMES}. */
    public static WindowFrames auto() {
        return AUTO;
    }

    /**
     * A stated number of frames per window.
     *
     * @throws IllegalArgumentException when the count is below {@link #MIN_FRAMES}
     */
    public static WindowFrames of(int frames) {
        if (frames < MIN_FRAMES) {
            throw new IllegalArgumentException("Macro option 'window_frames' must be '" + AUTO_VALUE
                    + "' or " + MIN_FRAMES + " frames or more, since a window of one frame holds no"
                    + " frame pair to measure (window_frames=" + frames + ").");
        }
        return new WindowFrames(false, frames);
    }

    /** True when the count has been left to the measured default. */
    public boolean isAuto() {
        return auto;
    }

    /** The frame count this resolves to, before the recording length is known. */
    public int resolved() {
        return frames;
    }

    /**
     * The frame count for a recording of the given length, floored at that
     * length: a window cannot be longer than the recording it sits in.
     *
     * @param recordingFrames how many frames the recording holds
     */
    public int resolvedFor(int recordingFrames) {
        return recordingFrames < frames ? recordingFrames : frames;
    }

    /** The text this is spelled with in a macro. */
    public String toMacroValue() {
        return auto ? AUTO_VALUE : Integer.toString(frames);
    }

    /**
     * Reads a {@code window_frames} macro value: {@code auto}, or a count.
     *
     * @throws IllegalArgumentException naming the option and the value
     */
    public static WindowFrames parse(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (AUTO_VALUE.equals(trimmed.toLowerCase(Locale.ROOT))) return AUTO;
        try {
            return of(Integer.parseInt(trimmed));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Macro option 'window_frames' must be '" + AUTO_VALUE
                    + "' or a number of frames (window_frames='" + trimmed + "').", e);
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof WindowFrames)) return false;
        WindowFrames that = (WindowFrames) other;
        return auto == that.auto && frames == that.frames;
    }

    @Override
    public int hashCode() {
        return (auto ? 1 : 0) * 31 + frames;
    }

    @Override
    public String toString() {
        return "window_frames=" + toMacroValue();
    }
}
