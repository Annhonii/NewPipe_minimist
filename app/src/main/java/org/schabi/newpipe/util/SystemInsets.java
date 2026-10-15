package org.schabi.newpipe.util;

/**
 * Current system bar sizes (status bar / navigation bar / display cutout) in pixels. The app
 * draws edge-to-edge, so pages that need to stay clear of the bars read the values from here.
 * Updated by {@link org.schabi.newpipe.MainActivity}.
 */
public final class SystemInsets {
    private static int top = 0;
    private static int bottom = 0;

    private SystemInsets() { }

    public static int getTop() {
        return top;
    }

    public static int getBottom() {
        return bottom;
    }

    public static void set(final int newTop, final int newBottom) {
        top = Math.max(0, newTop);
        bottom = Math.max(0, newBottom);
    }
}
