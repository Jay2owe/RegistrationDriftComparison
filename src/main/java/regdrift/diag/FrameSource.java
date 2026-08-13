/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.diag;

/**
 * The frames a measurement sees, as plain float planes at a stated scale.
 *
 * <p>This interface is the boundary that keeps the measurement code free of
 * ImageJ. Everything on the other side of it - which channel, which Z slice,
 * whether to project, how much to bin - has already been decided by the time a
 * plane arrives here, so the estimators, the descriptors and the arbiter can be
 * unit-tested against synthetic frames and can be handed pixels from somewhere
 * that is not an {@code ImagePlus} at all.
 *
 * <p><b>{@link #bin()} is part of the contract, not a convenience.</b> A plane
 * without the effective pixel size it was sampled at cannot be compared with
 * another plane, and the numbers measured from it cannot be compared with a
 * threshold. Carrying the scale on the pixels themselves is what stops it being
 * recorded once at the top of a run and then going stale - which is exactly how
 * defect D12 happened. It names {@link Frames.Bin}, which is a factor and a
 * clause of text and mentions no ImageJ class, so this interface stays as
 * ImageJ-free as it reads.
 *
 * <p>{@link #plane} may be called more than once for the same index, so an
 * implementation must either return a fresh array or guarantee that the array it
 * returns is never mutated afterwards.
 *
 * <p>Ported from {@code logratio\core\FrameSource.java} in the Log-Ratio
 * Registration research repository, with the scale added.
 */
public interface FrameSource {

    /** Number of frames along the time axis. */
    int count();

    /** Plane width in pixels, after any binning. */
    int width();

    /** Plane height in pixels, after any binning. */
    int height();

    /**
     * The effective pixel size these planes were sampled at. Never {@code null}:
     * a source at native resolution returns {@link Frames.Bin#none()}.
     */
    Frames.Bin bin();

    /**
     * Intensities for one frame, row-major, {@code width() * height()} long.
     *
     * <p>{@link Float#NaN} marks a pixel with no measurement; it is excluded
     * rather than propagated. Values may be negative - a 32-bit image is allowed
     * to be.
     *
     * @param frame zero-based
     */
    float[] plane(int frame);
}
