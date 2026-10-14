package org.schabi.newpipe.util;

import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

/**
 * Gives every fragment page an opaque background (only when it has none). While two pages slide
 * past each other the page below must not shine through, otherwise the transition looks like
 * a flicker.
 */
public final class OpaqueFragmentBackground
        extends FragmentManager.FragmentLifecycleCallbacks {

    public static void install(@NonNull final FragmentManager fragmentManager) {
        fragmentManager.registerFragmentLifecycleCallbacks(new OpaqueFragmentBackground(), true);
    }

    @Override
    public void onFragmentViewCreated(@NonNull final FragmentManager fm,
                                      @NonNull final Fragment f,
                                      @NonNull final View v,
                                      final android.os.Bundle savedInstanceState) {
        // only pages inside the main holder (not dialogs / the player fragment)
        if (f instanceof androidx.fragment.app.DialogFragment || v.getBackground() != null) {
            return;
        }
        if (f.getId() == org.schabi.newpipe.R.id.fragment_player_holder) {
            return;
        }
        final Context context = v.getContext();
        final TypedValue value = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.colorBackground, value, true)
                && value.type >= TypedValue.TYPE_FIRST_COLOR_INT
                && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            v.setBackgroundColor(value.data);
        } else {
            v.setBackgroundColor(Color.BLACK);
        }
    }
}
