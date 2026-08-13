/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.diag;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;
import regdrift.internal.PyramidCache;

/**
 * Presents an open image to the measurement as one 2-D plane per timepoint, at a
 * stated effective pixel size.
 *
 * <p>This class is the whole of "which pixels are measured, and how big is a
 * pixel". By the time a plane leaves here through {@link FrameSource}, the
 * channel, the Z slice, any projection and any binning have already been decided
 * - which is what lets everything downstream stay free of ImageJ and be tested
 * against synthetic frames.
 *
 * <h2>Binning, and why it is here rather than bolted on later</h2>
 *
 * <p>Binning means averaging each {@code n x n} square of pixels down to one
 * pixel: a 512x512 frame binned by 2 becomes 256x256, each new pixel the mean of
 * the four it replaces. Photographers call it downsampling; the point here is
 * that it fixes what "one pixel" means.
 *
 * <p>That matters because the registrability measure this plugin leads with -
 * {@link Localisability} - is the fall in frame-to-frame correlation when one
 * frame is displaced <em>one pixel</em>. One pixel at native resolution is a far
 * smaller relative displacement than one pixel after binning, so the quantity is
 * scale-dependent and the warning threshold that goes with it is scale-specific.
 * A threshold calibrated on binned frames, applied to native-resolution pixels,
 * warned on eleven of the twelve library recordings, including the four with
 * the largest reduction. That is defect D12, it is open, and stage 09 owns the
 * decision about what the scale should be. This class's job is to make the scale
 * a stated parameter that travels with the pixels, so it cannot be lost.
 *
 * <p>Binning is computed once per plane and cached with the plane, because every
 * stage that measures wants the same binned planes. It is never done per frame
 * pair.
 *
 * <h2>Which thread</h2>
 *
 * <p>An instance of this class reads an ImageJ stack, so it belongs to the
 * coordinator thread. Worker threads are handed the float arrays it produces,
 * never the instance. Reads of the underlying stack are serialised on the stack
 * itself, so two {@code Frames} over the same image - one per channel, as the
 * channel ranking builds - cannot ask ImageJ for two processors at once.
 *
 * <h2>Choosing the channel matters more than it looks</h2>
 *
 * <p>Both obvious heuristics are wrong. On a photon-limited recording the channel
 * with the most per-pixel contrast is usually the noisiest, not the most
 * structured: on a bioluminescence test stack the four channels had
 * frame-to-frame correlations of 0.063, 0.496, 0.992 and 0.995, and the first had
 * by far the largest mean gradient and was pure shot noise. Correlation is the
 * better of the two and is still not the right one - it is near one for a smooth
 * featureless blob as well as for real structure. {@link ChannelRanker} ranks on
 * localisability and reports correlation beside it.
 *
 * <p>Ported from {@code logratio\StackFrames.java} in the Log-Ratio Registration
 * research repository, split so that the ImageJ half is here and the float-array
 * half is {@link FrameSource}, with binning added.
 */
public final class Frames implements FrameSource {

    /** Passed as the slice to project across Z instead of taking one slice. */
    public static final int PROJECT_Z = 0;

    private final ImageStack stack;
    private final int nativeWidth;
    private final int nativeHeight;
    private final int width;
    private final int height;
    private final int frames;
    private final int channels;
    private final int slices;
    private final int channel;
    private final int slice;
    private final Bin bin;
    private final PyramidCache<float[]> planes;

    private Frames(ImagePlus imp, int channel, int slice, Bin bin) {
        if (imp == null) throw new IllegalArgumentException("no image to measure");
        if (bin == null) {
            throw new IllegalArgumentException("a frame source needs the scale it is sampled at;"
                    + " pass Frames.Bin.none() for native resolution. See defect D12");
        }
        this.stack = imp.getStack();
        this.nativeWidth = imp.getWidth();
        this.nativeHeight = imp.getHeight();
        this.channels = Math.max(1, imp.getNChannels());
        int declaredSlices = Math.max(1, imp.getNSlices());
        int declaredFrames = Math.max(1, imp.getNFrames());
        // Which axis is time. A plain stack - one channel, one frame declared, several slices - is
        // read as a time series, which is what an unlabelled TIFF from a microscope usually is; a
        // hyperstack states its own frame count and is taken at its word. This has to give the same
        // answer as the facade's frameCount, or the measurement would run over a different number of
        // frames than the run reports. FramesTest holds the two together.
        boolean slicesAreTime = declaredFrames <= 1 && channels == 1 && declaredSlices > 1;
        this.frames = slicesAreTime ? declaredSlices : declaredFrames;
        this.slices = slicesAreTime ? 1 : declaredSlices;
        this.channel = channel;
        this.slice = slice;
        this.bin = bin;
        this.width = bin.size(nativeWidth);
        this.height = bin.size(nativeHeight);
        if (channel < 1 || channel > channels) {
            throw new IllegalArgumentException("channel " + channel + " outside 1.." + channels);
        }
        if (slice != PROJECT_Z && (slice < 1 || slice > slices)) {
            throw new IllegalArgumentException("slice " + slice + " outside 1.." + slices);
        }
        this.planes = new PyramidCache<float[]>(new PyramidCache.Builder<float[]>() {
            @Override
            public float[] build(int frame) {
                return read(frame);
            }
        }, PyramidCache.capacityFor(this.frames, this.width, this.height,
                PyramidCache.PLANE_BYTES_PER_PIXEL, 2, 1, 0));
    }

    /**
     * Measure channel 1 at native resolution, projecting across Z if there is
     * more than one slice.
     */
    public static Frames of(ImagePlus imp) {
        return new Frames(imp, 1, PROJECT_Z, Bin.none());
    }

    /**
     * Measure one channel and one Z slice at native resolution.
     *
     * @param slice 1-based, or {@link #PROJECT_Z} to project across Z
     */
    public static Frames of(ImagePlus imp, int channel, int slice) {
        return new Frames(imp, channel, slice, Bin.none());
    }

    /**
     * Measure one channel and one Z slice, binned to a stated effective pixel
     * size.
     *
     * @param slice 1-based, or {@link #PROJECT_Z} to project across Z
     * @param bin   the scale to measure at; {@link Bin#none()} for native
     *              resolution. Never {@code null} - see the class note on D12
     */
    public static Frames of(ImagePlus imp, int channel, int slice, Bin bin) {
        return new Frames(imp, channel, slice, bin);
    }

    @Override
    public int count() {
        return frames;
    }

    /** Plane width after binning. */
    @Override
    public int width() {
        return width;
    }

    /** Plane height after binning. */
    @Override
    public int height() {
        return height;
    }

    @Override
    public Bin bin() {
        return bin;
    }

    /**
     * One frame, binned, as a fresh array.
     *
     * <p>Fresh on every call. The binned plane is cached, so the work is done
     * once, but the copy handed out is the caller's to do what it likes with -
     * the alternative is a shared array that one stage reuses and another quietly
     * writes into.
     */
    @Override
    public float[] plane(int frame) {
        if (frame < 0 || frame >= frames) {
            throw new IllegalArgumentException("frame " + frame + " outside 0.." + (frames - 1));
        }
        return planes.get(frame).clone();
    }

    /** Width before binning, as the image is stored. */
    public int nativeWidth() {
        return nativeWidth;
    }

    /** Height before binning, as the image is stored. */
    public int nativeHeight() {
        return nativeHeight;
    }

    /** The 1-based channel these planes come from. */
    public int channel() {
        return channel;
    }

    /** The 1-based Z slice, or {@link #PROJECT_Z} when Z is projected. */
    public int slice() {
        return slice;
    }

    /** How many channels the image has. */
    public int channels() {
        return channels;
    }

    /** How many Z slices the image has. */
    public int slices() {
        return slices;
    }

    /** Release the cached planes. Called when a run finishes. */
    public void release() {
        planes.clear();
    }

    @Override
    public String toString() {
        return "Frames[channel=" + channel
                + " slice=" + (slice == PROJECT_Z ? "project" : Integer.toString(slice))
                + " " + width + "x" + height + " x" + frames + " frames, " + bin + "]";
    }

    // --------------------------------------------------------------- reading

    /** Reads one frame from the stack and bins it. Called once per frame. */
    private float[] read(int frame) {
        float[] raw;
        // ImageJ's stack is not thread-safe and belongs to the coordinator. Serialising the read
        // on the stack itself keeps two Frames over the same image - one per channel - from asking
        // for two processors at once, while leaving the binning arithmetic outside the lock.
        synchronized (stack) {
            raw = projected(frame);
        }
        return bin.apply(raw, nativeWidth, nativeHeight);
    }

    private float[] projected(int frame) {
        if (slices == 1 || slice != PROJECT_Z) {
            int z = slices == 1 ? 1 : slice;
            return floats(index(frame, z));
        }
        // Maximum across Z. Maximum rather than mean because a mean dilutes a thin in-focus feature
        // with the out-of-focus slices either side of it, and the in-focus feature is the thing
        // whose displacement is being measured.
        float[] out = null;
        for (int z = 1; z <= slices; z++) {
            float[] p = floats(index(frame, z));
            if (out == null) {
                out = p;
            } else {
                for (int i = 0; i < out.length; i++) {
                    if (p[i] > out[i]) out[i] = p[i];
                }
            }
        }
        return out;
    }

    /** ImageJ lays a hyperstack out channel fastest, then Z, then time. */
    private int index(int frame, int z) {
        return (frame * channels * slices) + (z - 1) * channels + channel;
    }

    /**
     * A fresh float copy. Fresh because {@code ImageProcessor.toFloat} on a
     * 32-bit image would otherwise hand out the live pixel array.
     */
    private float[] floats(int stackIndex) {
        ImageProcessor ip = stack.getProcessor(stackIndex);
        float[] p = (float[]) ip.toFloat(0, null).getPixels();
        return ip instanceof ij.process.FloatProcessor ? p.clone() : p;
    }

    // ------------------------------------------------------------ the scale

    /**
     * The effective pixel size a measurement was taken at. Provenance, not
     * decoration.
     *
     * <p>A localisability number without this beside it cannot be read: the same
     * recording reads an order of magnitude differently at native resolution and
     * after binning, and the shipped warning threshold was calibrated at one of
     * those two scales. Every result object in the measurement carries one of
     * these, rather than the run recording it once at the top - a single object
     * holding a stale scale is how defect D12 happened.
     *
     * <p>Immutable, and cheap: a factor and the words that go beside it.
     */
    public static final class Bin {

        private static final Bin NONE = new Bin(1);

        private final int factor;

        private Bin(int factor) {
            this.factor = factor;
        }

        /** Native resolution: one image pixel is one measured pixel. */
        public static Bin none() {
            return NONE;
        }

        /**
         * An {@code n x n} mean.
         *
         * @param n at least 1; {@code 1} is the same as {@link #none()}
         * @throws IllegalArgumentException when {@code n} is below 1
         */
        public static Bin factor(int n) {
            if (n < 1) {
                throw new IllegalArgumentException("a binning factor is 1 or more, was " + n
                        + "; 1 means native resolution");
            }
            return n == 1 ? NONE : new Bin(n);
        }

        /** How many image pixels go into one measured pixel along each axis. */
        public int factor() {
            return factor;
        }

        /** True when nothing was binned away. */
        public boolean isNative() {
            return factor == 1;
        }

        /**
         * The scale in words, for the sentence beside a measurement.
         *
         * <p>The numeric form, {@link #factor()}, is what the
         * {@code measured_at_bin} column holds; this is what is written next to
         * it in the saved notes.
         */
        public String provenance() {
            return factor == 1 ? "native resolution" : factor + " x " + factor + " pixel mean";
        }

        /** The measured size of an axis that is {@code nativeSize} pixels long. */
        public int size(int nativeSize) {
            if (nativeSize <= 0) return 0;
            return (nativeSize + factor - 1) / factor;
        }

        /**
         * Bin one plane down to this scale, as the mean of each {@code n x n}
         * block.
         *
         * <p>{@link Float#NaN} pixels are left out of the block mean rather than
         * poisoning it, matching {@link FrameSource#plane}; a block with nothing
         * measured in it comes back {@code NaN}. A block at the right or bottom
         * edge that is not full is averaged over the pixels it does have.
         *
         * @return the plane as given when the factor is 1, otherwise a fresh
         *         array {@code size(width) * size(height)} long
         */
        public float[] apply(float[] plane, int width, int height) {
            if (plane == null) throw new IllegalArgumentException("no plane to bin");
            if (plane.length != width * height) {
                throw new IllegalArgumentException("expected a " + width + "x" + height
                        + " plane, got " + plane.length + " values");
            }
            if (factor == 1) return plane;
            int outWidth = size(width);
            int outHeight = size(height);
            float[] out = new float[outWidth * outHeight];
            for (int by = 0; by < outHeight; by++) {
                int y0 = by * factor;
                int y1 = Math.min(height, y0 + factor);
                for (int bx = 0; bx < outWidth; bx++) {
                    int x0 = bx * factor;
                    int x1 = Math.min(width, x0 + factor);
                    double total = 0;
                    int counted = 0;
                    for (int y = y0; y < y1; y++) {
                        int row = y * width;
                        for (int x = x0; x < x1; x++) {
                            float v = plane[row + x];
                            if (Float.isNaN(v)) continue;
                            total += v;
                            counted++;
                        }
                    }
                    out[by * outWidth + bx] = counted == 0 ? Float.NaN : (float) (total / counted);
                }
            }
            return out;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Bin)) return false;
            return factor == ((Bin) other).factor;
        }

        @Override
        public int hashCode() {
            return factor;
        }

        @Override
        public String toString() {
            return "bin=" + factor + " (" + provenance() + ")";
        }
    }
}
