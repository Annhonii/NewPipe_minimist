package org.schabi.newpipe.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import org.schabi.newpipe.R;

/**
 * ArchiveTune-style customisation: Material You (dynamic) accent colour, always on.
 * Also the option to disable animations. Applied as theme overlays on top of the base theme.
 */
public final class AppearanceHelper {
    private AppearanceHelper() { }

    public static boolean isDynamicColorSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    /** Applies the dynamic accent + animation overlays to the context's theme. */
    public static void applyOverlays(@NonNull final Context context) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        if (prefs.getBoolean(context.getString(R.string.disable_animations_key), false)) {
            context.getTheme().applyStyle(R.style.ThemeOverlay_App_NoAnimations, true);
        }
    }
}
