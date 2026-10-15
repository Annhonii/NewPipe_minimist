package org.schabi.newpipe.util;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.schabi.newpipe.R;

/**
 * Premium list entrance: rows rise a few dp and fade in, with a short stagger between the rows
 * that appear together. Rows that already played their entrance (scrolling back up) are left
 * alone, and the sequence restarts whenever the adapter's content is replaced (new search,
 * refresh, layout switch).
 *
 * <p>Install once per RecyclerView with {@link #install(RecyclerView)}. It is purely visual:
 * it never touches the adapter, the layout manager or the item animator.</p>
 */
public final class ListMotion {
    private static final boolean ENABLED = false;
    private static final long DURATION_MS = 380L;
    private static final long STAGGER_MS = 42L;
    private static final int MAX_STAGGER_STEPS = 8;
    private static final long BATCH_WINDOW_MS = 160L;
    private static final float RISE_DP = 22f;
    private static final Interpolator EMPHASIZED_DECELERATE =
            new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);

    private ListMotion() { }

    /** Per list bookkeeping, kept in a view tag so nothing leaks. */
    private static final class State {
        int highestAnimated = -1;
        long lastAttachTime = 0L;
        int batchIndex = 0;
        RecyclerView.Adapter<?> observedAdapter;
        final RecyclerView.AdapterDataObserver observer = new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                reset();
            }

            @Override
            public void onItemRangeRemoved(final int positionStart, final int itemCount) {
                // removing the loading footer at the end must not restart the sequence
                if (positionStart == 0) {
                    reset();
                }
            }
        };

        void reset() {
            highestAnimated = -1;
            batchIndex = 0;
        }
    }

    public static void install(@NonNull final RecyclerView list) {
        // Entrance animation disabled: it could leave rows at alpha 0 (blank list) when
        // scrolling fast or together with other animations.
        if (!ENABLED) {
            return;
        }
        if (list.getTag(R.id.list_motion_state) != null) {
            return;
        }
        final State state = new State();
        list.setTag(R.id.list_motion_state, state);
        list.addOnChildAttachStateChangeListener(
                new RecyclerView.OnChildAttachStateChangeListener() {
                    @Override
                    public void onChildViewAttachedToWindow(@NonNull final View view) {
                        onAttached(list, state, view);
                    }

                    @Override
                    public void onChildViewDetachedFromWindow(@NonNull final View view) {
                        settle(view);
                    }
                });
    }

    private static void onAttached(@NonNull final RecyclerView list, @NonNull final State state,
                                   @NonNull final View child) {
        // follow the adapter, so a content swap restarts the sequence
        final RecyclerView.Adapter<?> adapter = list.getAdapter();
        if (adapter != state.observedAdapter) {
            if (state.observedAdapter != null) {
                try {
                    state.observedAdapter.unregisterAdapterDataObserver(state.observer);
                } catch (final IllegalStateException ignored) {
                    // not registered any more
                }
            }
            state.observedAdapter = adapter;
            if (adapter != null) {
                adapter.registerAdapterDataObserver(state.observer);
            }
            state.reset();
        }

        final int position = list.getChildAdapterPosition(child);
        if (position == RecyclerView.NO_POSITION || position <= state.highestAnimated
                || !animationsEnabled(list.getContext())) {
            return;
        }
        state.highestAnimated = position;

        final long now = System.currentTimeMillis();
        if (now - state.lastAttachTime > BATCH_WINDOW_MS) {
            state.batchIndex = 0;
        }
        state.lastAttachTime = now;
        final long delay = Math.min(state.batchIndex++, MAX_STAGGER_STEPS) * STAGGER_MS;

        // remember the alpha the row wants to have (some rows are intentionally dimmed)
        final float targetAlpha = child.getAlpha();
        child.setTag(R.id.list_motion_alpha, targetAlpha);
        child.animate().cancel();
        child.setAlpha(0f);
        child.setTranslationY(RISE_DP * list.getResources().getDisplayMetrics().density);
        child.animate()
                .alpha(targetAlpha)
                .translationY(0f)
                .setStartDelay(delay)
                .setDuration(DURATION_MS)
                .setInterpolator(EMPHASIZED_DECELERATE)
                .start();
    }

    /** Ends a running entrance immediately, so a recycled row never stays half transparent. */
    private static void settle(@NonNull final View child) {
        final Object target = child.getTag(R.id.list_motion_alpha);
        if (target instanceof Float) {
            child.animate().cancel();
            child.setAlpha((Float) target);
            child.setTranslationY(0f);
            child.setTag(R.id.list_motion_alpha, null);
        }
    }

    private static boolean animationsEnabled(@NonNull final Context context) {
        try {
            final ContentResolver resolver = context.getContentResolver();
            return Settings.Global.getFloat(resolver,
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
        } catch (final Exception e) {
            return true;
        }
    }
}
