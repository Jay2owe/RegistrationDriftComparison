/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The recordings a dialog can offer, and the shape of each one.
 *
 * <p>A plain value type carrying window titles and dimensions rather than
 * images. Two reasons it is not a list of {@code ImagePlus}:
 *
 * <ul>
 *   <li>The dialogs then hold no ImageJ state at all, so what a dropdown offers
 *       and what the channel list says can be checked by a test that has neither
 *       an open window nor a screen.</li>
 *   <li>Reading the open windows is the entry class's job, on the coordinator
 *       thread, which is where the plugin's house rules put every touch of
 *       ImageJ state. The dialog is handed the answer.</li>
 * </ul>
 */
public final class ImageChoices {

    private final List<Entry> entries;
    private final String activeTitle;

    private ImageChoices(List<Entry> entries, String activeTitle) {
        this.entries = Collections.unmodifiableList(new ArrayList<Entry>(entries));
        this.activeTitle = activeTitle == null ? "" : activeTitle;
    }

    /**
     * The recordings on offer.
     *
     * @param entries     one per open window, in the order they should be listed
     * @param activeTitle the window the dialog starts on, which is the front one
     */
    public static ImageChoices of(List<Entry> entries, String activeTitle) {
        List<Entry> given = entries == null ? new ArrayList<Entry>() : entries;
        String active = activeTitle;
        if ((active == null || active.isEmpty()) && !given.isEmpty()) {
            active = given.get(0).title();
        }
        return new ImageChoices(given, active);
    }

    /** No recordings at all. */
    public static ImageChoices none() {
        return new ImageChoices(new ArrayList<Entry>(), "");
    }

    /** True when there is nothing to measure. */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** How many recordings are on offer. */
    public int size() {
        return entries.size();
    }

    /** The window the dialog starts on. Empty when there is nothing to start on. */
    public String activeTitle() {
        return activeTitle;
    }

    /** Every window title, in the order they should be listed. */
    public String[] titles() {
        String[] titles = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) titles[i] = entries.get(i).title();
        return titles;
    }

    /** Every recording on offer. */
    public List<Entry> entries() {
        return entries;
    }

    /** The recording with this window title, or null when there is no such window. */
    public Entry find(String title) {
        for (Entry entry : entries) {
            if (entry.title().equals(title)) return entry;
        }
        return null;
    }

    /** The recording the dialog starts on, or null when there is none. */
    public Entry active() {
        return find(activeTitle);
    }

    /** One open recording: what it is called and how it is shaped. */
    public static final class Entry {

        private final String title;
        private final int channels;
        private final int slices;
        private final int frames;

        /**
         * @param title    the window title, which is how a macro names it
         * @param channels how many channels it holds, at least one
         * @param slices   how many Z slices it holds, at least one
         * @param frames   how many time points it holds
         */
        public Entry(String title, int channels, int slices, int frames) {
            this.title = title == null ? "" : title;
            this.channels = Math.max(1, channels);
            this.slices = Math.max(1, slices);
            this.frames = Math.max(0, frames);
        }

        /** The window title. */
        public String title() {
            return title;
        }

        /** How many channels. */
        public int channels() {
            return channels;
        }

        /** How many Z slices. */
        public int slices() {
            return slices;
        }

        /** How many time points. */
        public int frames() {
            return frames;
        }

        /** A short description of the shape, for the line under the dropdown. */
        public String shape() {
            return frames + (frames == 1 ? " frame, " : " frames, ")
                    + channels + (channels == 1 ? " channel, " : " channels, ")
                    + slices + (slices == 1 ? " Z slice" : " Z slices");
        }

        @Override
        public String toString() {
            return title + " (" + shape() + ")";
        }
    }
}
