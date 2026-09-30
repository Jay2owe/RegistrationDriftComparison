/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift;

import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Roi;
import ij.process.ImageProcessor;

import java.awt.Rectangle;

/**
 * Reads the selection {@code use_roi} measures inside.
 *
 * <p>Its own class so that {@link RegDrift}, the public entry point, names no
 * {@code ij.gui} type: a selection is data, but the package it lives in is the
 * one that opens windows, and {@code ApiIsolationTest} holds the entry point
 * clear of all of it.
 */
final class Selection {

    /** The image property carrying the sentence that says what was measured. */
    static final String NOTE_PROPERTY = "regdrift.roiNote";

    private Selection() {
    }

    /**
     * The part of the recording {@code use_roi} measures: a copy of every plane
     * cropped to the bounding rectangle of the selection, or the reason there is
     * none.
     *
     * <p>A rectangle because both estimators measure whole-field translation over
     * a rectangular plane; a selection of another shape is measured over the
     * rectangle that encloses it, and the record says so. Only the measurement
     * (the Diagnosis table and the recommendation keyed on it) is restricted.
     * Engines register the whole recording and every arm is scored over the whole
     * frame, because that is what an engine hands back.
     *
     * @return an {@link ImagePlus}, or a {@link Failure} naming what is wrong with
     *         the selection
     */
    static Object measuredImage(ImagePlus image) {
        Roi roi = image.getRoi();
        String title = image.getTitle();
        if (roi == null || !roi.isArea()) {
            return Failure.of(Failure.Kind.INVALID_PARAMETERS, "'" + title + "' has no area"
                    + " selection, and '" + RegDriftMacroOptions.USE_ROI + "' asks for the movement"
                    + " to be measured inside one. Draw a selection on the recording, or set '"
                    + RegDriftMacroOptions.USE_ROI + "' to false to measure the whole frame.");
        }
        Rectangle bounds = roi.getBounds()
                .intersection(new Rectangle(0, 0, image.getWidth(), image.getHeight()));
        if (bounds.isEmpty() || bounds.width < RegDrift.MIN_ROI_SIDE_PX || bounds.height < RegDrift.MIN_ROI_SIDE_PX) {
            int w = bounds.isEmpty() ? 0 : bounds.width;
            int h = bounds.isEmpty() ? 0 : bounds.height;
            return Failure.of(Failure.Kind.INVALID_PARAMETERS, "The selection on '" + title
                    + "' covers " + w + " x " + h + " pixels inside the image, and measuring"
                    + " movement inside a selection needs at least " + RegDrift.MIN_ROI_SIDE_PX + " x "
                    + RegDrift.MIN_ROI_SIDE_PX + ". Draw a larger selection, or set '"
                    + RegDriftMacroOptions.USE_ROI + "' to false to measure the whole frame.");
        }
        ImageStack source = image.getStack();
        ImageStack crop = new ImageStack(bounds.width, bounds.height);
        synchronized (source) {
            for (int i = 1; i <= source.getSize(); i++) {
                ImageProcessor ip = source.getProcessor(i);
                ip.setRoi(bounds);
                crop.addSlice(source.getSliceLabel(i), ip.crop());
                ip.resetRoi();
            }
        }
        ImagePlus cropped = new ImagePlus(title, crop);
        cropped.setDimensions(image.getNChannels(), image.getNSlices(), image.getNFrames());
        if (image.isHyperStack()) cropped.setOpenAsHyperStack(true);
        cropped.setCalibration(image.getCalibration());
        cropped.setProperty(NOTE_PROPERTY, "Measured inside the "
                + (roi.getType() == Roi.RECTANGLE ? "rectangular selection" : "rectangle"
                + " enclosing the selection") + " at x=" + bounds.x + ", y=" + bounds.y + ", "
                + bounds.width + " x " + bounds.height + " pixels (" + RegDriftMacroOptions.USE_ROI
                + "); engines and scoring use the whole frame.");
        return cropped;
    }
}
