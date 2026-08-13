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
 * Displacement by sliding one frame over the other until the squared difference
 * between them is smallest, done on a stack of progressively blurrier copies.
 *
 * <p>The everyday version: to find where a photograph sits on top of another,
 * first shrink both to thumbnails and try every position on the thumbnail, which
 * is cheap because there are few positions to try. Then take that answer to the
 * next size up, nudge it, and keep going until you are back at full size and
 * nudging by fractions of a pixel.
 *
 * <p><b>This is deliberately the naive method.</b> No gain model, no robust
 * weighting, no choosing which pixels are allowed to vote - a plain sum of
 * squared differences on raw intensities, because that is what StackReg,
 * TurboReg and Image Stabilizer minimise, and what the survey measured as the
 * {@code SSD no gain (StackReg family)} arm. Making it cleverer would make it a
 * worse baseline, not a better one.
 *
 * <p>Published prior art, and old: Lucas and Kanade, <i>IJCAI</i> 1981, for the
 * criterion; Burt and Adelson, <i>IEEE Transactions on Communications</i> 31:532,
 * 1983, for the pyramid; Hooke and Jeeves, <i>Journal of the ACM</i> 8:212, 1961,
 * for the pattern search used to descend it. All three predate everything in
 * this repository.
 *
 * <h2>Why this was written rather than borrowed</h2>
 *
 * <p>There is a working pyramid search in the author's own registration
 * repository, and configuring it with a least-squares norm and its gain model
 * switched off produces exactly this criterion. Using it would have been a
 * hundred lines instead of three hundred, and it would have made the two
 * estimators in this package share the alignment machinery, the pyramid
 * schedule, the convergence rule and the pixel-support selection with each other
 * and with the method the recommendation is supposed to be independent of. The
 * independence claim is the product, so this is written fresh, and it descends
 * its pyramid by pattern search rather than by Gauss-Newton with a backtracking
 * line search, which is the other repository's method.
 *
 * <p>What was read from that repository, and read only: that an exhaustive
 * integer sweep at the coarsest level is what picks the right basin, and that
 * the sweep must score the identity first and require a candidate to beat it
 * strictly. Both are standard, and the second is defect D8.
 *
 * <h2>The featureless pair, which is defect D8</h2>
 *
 * <p>On a frame with no texture - blank, saturated, or all background - every
 * candidate position scores identically, and a sweep that takes the first
 * improvement it scans returns whichever corner of the search box it happened to
 * start in. Measured before this was understood: a flat 192x192 pair produced a
 * confident 45 px displacement. Here zero is the incumbent, it is <em>scored</em>
 * rather than assumed, and a candidate must beat it strictly; on top of that a
 * pair with no variation in it at all is answered
 * {@link Estimator.Status#NO_STRUCTURE} before any search starts.
 *
 * <h2>The search bound</h2>
 *
 * <p>{@code maxShift} is an argument on every path. Nothing here stores one and
 * nothing here derives one from the pair in front of it - see {@link Estimator}.
 *
 * <p><b>What the bound status cannot tell anybody.</b> When the true displacement
 * is a little past the bound, the search runs up against it and says so. When it
 * is far past - a 10 px step against a 3 px bound - the search never gets near
 * the right basin at all, settles in some other minimum with room to spare, and
 * reports {@link Estimator.Status#OK}, because as far as it can see it did settle.
 * Content whose structure repeats every ten pixels or so, which is what a field
 * of cells is, has minima all over a disc that size. Nothing in a local search can
 * detect this; two things outside it guard against it, and they are the reason the
 * fingerprint is built the way it is. The bound is derived from the whole
 * recording rather than guessed, and the second estimator lands somewhere else,
 * which is what the agreement column reports.
 */
public final class PyramidSsd implements Estimator {

    /** The published name of the method. */
    public static final String NAME = "pyramid sum of squared differences";

    /**
     * The coarsest level is never smaller than this on either axis.
     *
     * <p>Below about this size a microscope frame has been blurred down to a
     * handful of blobs, and the sweep that is supposed to pick the right basin
     * picks whichever blob is nearest instead.
     */
    static final int MIN_COARSE_SIZE = 32;

    /** Never more than this many levels, however large the frame or the bound. */
    static final int MAX_LEVELS = 5;

    /**
     * The sweep radius aimed for at the coarsest level, in that level's pixels.
     *
     * <p>The exhaustive sweep costs {@code (2r+1)^2} evaluations, so the level
     * count is chosen to bring the bound down to about this. Six is 169
     * evaluations on a plane of at most a few thousand pixels.
     */
    static final int COARSE_RADIUS = 6;

    /** Positions the pattern search may score at one pyramid level before it gives up. */
    static final int EVALUATIONS_PER_LEVEL = 48;

    /** Positions it may score at full resolution, where the step also shrinks to sub-pixel. */
    static final int EVALUATIONS_FINE = 200;

    /** The search stops shrinking its step here. Well below what the pixels can support. */
    static final double SUBPIXEL_TOLERANCE = 0.005;

    /**
     * A candidate must beat the incumbent by more than this fraction of it.
     *
     * <p>Relative, so that "strictly better" means the same thing on a frame of
     * counts in the thousands and on one in the tens. On a featureless pair the
     * incumbent scores zero, this reduces to "strictly less than zero", and a sum
     * of squares never is - which is exactly the behaviour defect D8 asks for.
     */
    static final double IMPROVEMENT_TOLERANCE = 1e-12;

    /**
     * Below this fraction of the frame still overlapping, a candidate position is
     * not scored at all.
     *
     * <p>Geometry, not pixel selection: at a large displacement most of one frame
     * has slid off the other, and a mean squared difference over the sliver that
     * is left is a number about the sliver.
     */
    static final double MIN_OVERLAP = 0.25;

    /** At most this many pixels are visited per evaluation; beyond it a regular stride is used. */
    static final int MAX_SAMPLES = 250_000;

    @Override
    public String name() {
        return NAME;
    }

    /**
     * Displacement of the content from {@code a} to {@code b}.
     *
     * <p>Builds a pyramid for each frame and then searches it. A caller measuring
     * many pairs over overlapping frames should build each frame's pyramid once
     * with {@link #pyramid} and call {@link #shiftOf} per pair instead; this
     * method is the single-pair convenience and the {@link Estimator} contract.
     */
    @Override
    public Displacement shift(float[] a, float[] b, int width, int height, double maxShift,
                              Frames.Bin bin) {
        int levels = levelsFor(width, height, maxShift);
        return shiftOf(pyramid(a, width, height, levels), pyramid(b, width, height, levels),
                width, height, maxShift, bin);
    }

    /**
     * How many pyramid levels a frame of this size wants for this bound.
     *
     * <p>Two pressures, and the smaller wins. Enough levels that the exhaustive
     * sweep at the top is about {@link #COARSE_RADIUS} pixels across; never so
     * many that the coarsest level falls below {@link #MIN_COARSE_SIZE} on either
     * axis, and never more than {@link #MAX_LEVELS}.
     *
     * @return at least 1, which means full resolution and nothing above it
     */
    public static int levelsFor(int width, int height, double maxShift) {
        int affordable = 1;
        int smaller = Math.min(width, height);
        while (affordable < MAX_LEVELS && smaller / 2 >= MIN_COARSE_SIZE) {
            smaller /= 2;
            affordable++;
        }
        int wanted = 1;
        double reach = Math.max(0, maxShift);
        while (wanted < affordable && reach > COARSE_RADIUS) {
            reach /= 2;
            wanted++;
        }
        return Math.max(1, Math.min(affordable, wanted));
    }

    /** Width of a pyramid level. Each level is half the one below it, rounded up. */
    public static int widthAt(int width, int level) {
        return sizeAt(width, level);
    }

    /** Height of a pyramid level. */
    public static int heightAt(int height, int level) {
        return sizeAt(height, level);
    }

    private static int sizeAt(int size, int level) {
        int out = size;
        for (int l = 0; l < level; l++) out = (out + 1) / 2;
        return Math.max(1, out);
    }

    /**
     * One frame's pyramid: the plane itself, then each level the mean of every
     * {@code 2 x 2} block of the level below.
     *
     * <p>Depends on the frame alone, so it caches cleanly and a frame appearing in
     * two pairs is reduced once. {@link Float#NaN} pixels are left out of a block
     * mean rather than poisoning it; a block with nothing measured in it stays
     * {@code NaN}.
     *
     * @param levels how many levels including full resolution, from
     *               {@link #levelsFor}
     * @return level 0 is the plane as given, not a copy. Nothing here writes to it
     */
    public static float[][] pyramid(float[] plane, int width, int height, int levels) {
        if (plane == null) throw new IllegalArgumentException("no plane to reduce");
        if (plane.length != width * height) {
            throw new IllegalArgumentException("expected a " + width + "x" + height
                    + " plane, got " + plane.length + " values");
        }
        if (levels < 1) throw new IllegalArgumentException("a pyramid has at least one level");
        float[][] out = new float[levels][];
        out[0] = plane;
        int w = width;
        int h = height;
        for (int l = 1; l < levels; l++) {
            int nw = sizeAt(width, l);
            int nh = sizeAt(height, l);
            out[l] = halve(out[l - 1], w, h, nw, nh);
            w = nw;
            h = nh;
        }
        return out;
    }

    /**
     * Displacement of the content from the frame behind {@code a} to the frame
     * behind {@code b}, from their pyramids.
     *
     * @param width    full-resolution width both pyramids were built at
     * @param maxShift bound on the magnitude of the answer, in the pixels the
     *                 frames were sampled at
     * @param bin      that pixel size. Required
     */
    public static Displacement shiftOf(float[][] a, float[][] b, int width, int height,
                                       double maxShift, Frames.Bin bin) {
        if (a == null || b == null) throw new IllegalArgumentException("no pyramids to search");
        if (a.length != b.length || a.length < 1) {
            throw new IllegalArgumentException("the two pyramids have " + a.length + " and "
                    + b.length + " levels");
        }
        if (a[0].length != width * height || b[0].length != width * height) {
            throw new IllegalArgumentException("expected two " + width + "x" + height + " frames");
        }
        if (!(maxShift > 0)) {
            throw new IllegalArgumentException("the search bound must be a positive number of"
                    + " pixels, was " + maxShift);
        }
        // Nothing to slide. A frame whose measured pixels are all the same value scores the same at
        // every position, so the search would return whichever position it examined first. Answering
        // the identity with a reason on it is defect D8's fix stated before the search rather than
        // inside it.
        if (!varies(a[0]) || !varies(b[0])) {
            return Displacement.identity(Status.NO_STRUCTURE, bin);
        }

        int top = a.length - 1;
        double scale = 1 << top;
        Search at = sweep(a[top], b[top], sizeAt(width, top), sizeAt(height, top), maxShift / scale);
        if (Double.isInfinite(at.cost)) {
            // Not even the identity could be scored: the two frames have too little measured area
            // in common. Nothing after this would be a measurement of anything.
            return Displacement.identity(Status.NO_STRUCTURE, bin);
        }
        boolean settled = at.settled;

        for (int level = top - 1; level >= 0; level--) {
            scale = 1 << level;
            int lw = sizeAt(width, level);
            int lh = sizeAt(height, level);
            // One level down is twice the displacement in that level's own pixels.
            double dx = at.dx * 2;
            double dy = at.dy * 2;
            boolean fine = level == 0;
            at = compass(a[level], b[level], lw, lh, dx, dy, 1.0,
                    fine ? SUBPIXEL_TOLERANCE : 1.0, maxShift / scale,
                    fine ? EVALUATIONS_FINE : EVALUATIONS_PER_LEVEL);
            settled = settled && at.settled;
        }
        if (top == 0) {
            // A single-level pyramid: the sweep found the integer minimum and nothing has refined
            // it to sub-pixel yet.
            at = compass(a[0], b[0], width, height, at.dx, at.dy, 0.5, SUBPIXEL_TOLERANCE,
                    maxShift, EVALUATIONS_FINE);
            settled = settled && at.settled;
        }

        double magnitude = Math.hypot(at.dx, at.dy);
        if (magnitude >= maxShift * (1 - 1e-9)) {
            return Displacement.of(at.dx, at.dy, Status.AT_SHIFT_BOUND, bin);
        }
        return Displacement.of(at.dx, at.dy, settled ? Status.OK : Status.NOT_CONVERGED, bin);
    }

    // ------------------------------------------------------------ the search

    /** Where the search has got to, and whether it stopped because it had finished. */
    private static final class Search {
        final double dx;
        final double dy;
        final double cost;
        final boolean settled;

        Search(double dx, double dy, double cost, boolean settled) {
            this.dx = dx;
            this.dy = dy;
            this.cost = cost;
            this.settled = settled;
        }
    }

    /**
     * Exhaustive integer sweep of the whole search box at the coarsest level.
     *
     * <p>This is what picks the basin. A local search alone would settle into
     * whichever minimum it started nearest, and on a frame with repeating
     * structure - a field of cells, a grid of wells - there are many.
     *
     * <p>Zero is scored first and a candidate must beat it strictly. That is
     * defect D8, and it is the difference between answering "it did not move" and
     * answering "45 pixels, toward the corner of my own search box" on a blank
     * pair.
     */
    private static Search sweep(float[] a, float[] b, int w, int h, double bound) {
        int stride = strideFor(w, h);
        int radius = (int) Math.ceil(bound);
        double best = cost(a, b, w, h, 0, 0, stride);
        double bestDx = 0;
        double bestDy = 0;
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx == 0 && dy == 0) continue;
                // The radius is rounded up, so it reaches past the bound the caller set. A sweep
                // that ignored that could return a displacement the caller forbade and then have it
                // doubled at every level on the way down.
                if (Math.hypot(dx, dy) > bound + 1e-9) continue;
                double here = cost(a, b, w, h, dx, dy, stride);
                if (beats(here, best)) {
                    best = here;
                    bestDx = dx;
                    bestDy = dy;
                }
            }
        }
        return new Search(bestDx, bestDy, best, true);
    }

    /**
     * Pattern search: try a step along each axis in turn, move to the first
     * position that is strictly better, and halve the step when no direction is.
     *
     * <p>Hooke and Jeeves' compass search, and the reason it is used here rather
     * than a derivative method is that it needs nothing from the pixels except
     * the ability to score a position. No image gradients, no Jacobian, no
     * Hessian, no line search, no weights - which is what keeps this estimator
     * structurally unrelated to the other pyramid search in the portfolio.
     *
     * <p>Called with {@code firstStep = 1} and {@code tolerance = 1} it is an
     * integer refinement; called with {@code tolerance} well below a pixel it
     * carries straight on into sub-pixel positions, where {@link #cost} samples
     * between pixels.
     *
     * @return the position reached, and {@code settled} false if it ran out of
     *         steps while still improving
     */
    private static Search compass(float[] a, float[] b, int w, int h, double dx0, double dy0,
                                  double firstStep, double tolerance, double bound, int budget) {
        int stride = strideFor(w, h);
        // The starting position can arrive from the level above sitting outside this level's bound,
        // because doubling it doubled the displacement but not the bound. Bring it back first.
        double back = boundFactor(dx0, dy0, bound);
        double dx = dx0 * back;
        double dy = dy0 * back;
        double best = cost(a, b, w, h, dx, dy, stride);
        double step = firstStep;
        int spent = 0;
        while (step >= tolerance) {
            boolean moved = false;
            for (int direction = 0; direction < 4 && spent < budget; direction++) {
                double tx = dx + (direction == 0 ? step : direction == 1 ? -step : 0);
                double ty = dy + (direction == 2 ? step : direction == 3 ? -step : 0);
                double f = boundFactor(tx, ty, bound);
                tx *= f;
                ty *= f;
                if (tx == dx && ty == dy) continue;
                spent++;
                double here = cost(a, b, w, h, tx, ty, stride);
                if (beats(here, best)) {
                    best = here;
                    dx = tx;
                    dy = ty;
                    moved = true;
                    break;
                }
            }
            if (spent >= budget) return new Search(dx, dy, best, false);
            if (!moved) step /= 2;
        }
        return new Search(dx, dy, best, true);
    }

    /**
     * What to multiply a position by to bring it back onto the bound, keeping its direction. One
     * when it is already inside.
     *
     * <p>Projected rather than discarded, and that is what makes
     * {@link Estimator.Status#AT_SHIFT_BOUND} mean something. Discarding out-of-bound candidates
     * would leave the search stranded at whichever position inside the bound it last improved on -
     * typically a pixel or two short of it - and the answer would come back
     * {@link Estimator.Status#OK}, indistinguishable from a real measurement, when in fact the
     * pixels wanted to go further than the caller allowed.
     */
    private static double boundFactor(double dx, double dy, double bound) {
        double magnitude = Math.hypot(dx, dy);
        return magnitude <= bound || magnitude == 0 ? 1.0 : bound / magnitude;
    }

    /**
     * Strictly better, by a margin that scales with the number itself.
     *
     * <p>The whole of defect D8 is the word "strictly" and the fact that the
     * incumbent was scored before the loop rather than assumed to be infinitely
     * bad.
     */
    private static boolean beats(double candidate, double incumbent) {
        if (Double.isInfinite(incumbent)) return !Double.isInfinite(candidate);
        return candidate < incumbent - IMPROVEMENT_TOLERANCE * Math.abs(incumbent);
    }

    /**
     * Mean squared difference between {@code a} and {@code b} when the content is
     * taken to have moved by {@code (dx, dy)}.
     *
     * <p>A feature at {@code p} in {@code a} sits at {@code p + d} in {@code b},
     * so this compares {@code a} at each position with {@code b} at that position
     * plus the displacement. That is the project's sign convention, and getting it
     * backwards here would produce a motion trace that is exactly wrong and
     * entirely plausible.
     *
     * <p>A mean rather than a sum, because different positions leave different
     * numbers of pixels overlapping and a sum would reward sliding the frames
     * apart. Fractional positions are sampled between pixels; whole ones are read
     * straight out, so an integer position costs no interpolation and loses no
     * edge row.
     *
     * @return {@link Double#POSITIVE_INFINITY} when too little of the frame is
     *         left overlapping to say anything
     */
    private static double cost(float[] a, float[] b, int w, int h, double dx, double dy,
                               int stride) {
        int x0 = (int) Math.floor(dx);
        int y0 = (int) Math.floor(dy);
        double fx = dx - x0;
        double fy = dy - y0;
        boolean wholeX = fx == 0;
        boolean wholeY = fy == 0;
        double total = 0;
        int counted = 0;
        int possible = 0;
        for (int y = 0; y < h; y += stride) {
            int sy = y + y0;
            int row = y * w;
            for (int x = 0; x < w; x += stride) {
                float av = a[row + x];
                if (Float.isNaN(av)) continue;
                possible++;
                int sx = x + x0;
                if (sx < 0 || sy < 0) continue;
                int sx1 = wholeX ? sx : sx + 1;
                int sy1 = wholeY ? sy : sy + 1;
                if (sx1 >= w || sy1 >= h) continue;
                float p00 = b[sy * w + sx];
                float p10 = b[sy * w + sx1];
                float p01 = b[sy1 * w + sx];
                float p11 = b[sy1 * w + sx1];
                if (Float.isNaN(p00) || Float.isNaN(p10) || Float.isNaN(p01) || Float.isNaN(p11)) {
                    continue;
                }
                double top = p00 + fx * (p10 - p00);
                double bottom = p01 + fx * (p11 - p01);
                double bv = top + fy * (bottom - top);
                double r = bv - av;
                total += r * r;
                counted++;
            }
        }
        if (counted == 0 || counted < MIN_OVERLAP * possible) return Double.POSITIVE_INFINITY;
        return total / counted;
    }

    /** True when the measured pixels of a plane are not all the same value. */
    private static boolean varies(float[] plane) {
        float first = Float.NaN;
        for (int i = 0; i < plane.length; i++) {
            float v = plane[i];
            if (Float.isNaN(v)) continue;
            if (Float.isNaN(first)) {
                first = v;
            } else if (v != first) {
                return true;
            }
        }
        return false;
    }

    /**
     * How many pixels to step over when visiting a level.
     *
     * <p>A cost control and nothing more: the lattice is regular and depends only
     * on the size of the plane, so it is not a choice about <em>which</em> pixels
     * deserve to vote. That kind of choice is exactly what this estimator does not
     * make.
     */
    private static int strideFor(int w, int h) {
        long pixels = (long) w * h;
        if (pixels <= MAX_SAMPLES) return 1;
        return Math.max(1, (int) Math.ceil(Math.sqrt((double) pixels / MAX_SAMPLES)));
    }

    /** One level down: the mean of each 2 x 2 block, skipping unmeasured pixels. */
    private static float[] halve(float[] src, int w, int h, int nw, int nh) {
        float[] out = new float[nw * nh];
        for (int y = 0; y < nh; y++) {
            int y0 = 2 * y;
            int y1 = Math.min(h, y0 + 2);
            for (int x = 0; x < nw; x++) {
                int x0 = 2 * x;
                int x1 = Math.min(w, x0 + 2);
                double total = 0;
                int counted = 0;
                for (int sy = y0; sy < y1; sy++) {
                    int row = sy * w;
                    for (int sx = x0; sx < x1; sx++) {
                        float v = src[row + sx];
                        if (Float.isNaN(v)) continue;
                        total += v;
                        counted++;
                    }
                }
                out[y * nw + x] = counted == 0 ? Float.NaN : (float) (total / counted);
            }
        }
        return out;
    }
}
