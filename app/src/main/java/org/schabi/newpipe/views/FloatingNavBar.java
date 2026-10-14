package org.schabi.newpipe.views;

import android.animation.AnimatorInflater;
import android.content.Context;
import android.content.res.ColorStateList;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.viewpager.widget.ViewPager;

import org.schabi.newpipe.R;
import org.schabi.newpipe.util.ThemeHelper;

import java.util.List;

/**
 * Floating bottom navigation: a capsule with morphing tabs ({@link MorphingNavPill}) and,
 * next to it, optional round action buttons (downloads, history, ...).
 *
 * <p>Typical use:</p>
 * <pre>
 * bar.setItems(items);
 * bar.setActions(actions);
 * bar.attachToViewPager(pager);   // the pages slide and the bar morphs in sync
 * </pre>
 */
public class FloatingNavBar extends LinearLayout {
    /** A round button beside the pill. */
    public static final class Action {
        @DrawableRes
        final int iconRes;
        @NonNull
        final CharSequence description;
        @NonNull
        final Runnable onClick;

        public Action(@DrawableRes final int iconRes, @NonNull final CharSequence description,
                      @NonNull final Runnable onClick) {
            this.iconRes = iconRes;
            this.description = description;
            this.onClick = onClick;
        }
    }

    /** Reports the selected tab, whether it was chosen by tap, swipe or code. */
    public interface OnItemSelectedListener {
        void onItemSelected(int index);

        void onItemReselected(int index);
    }

    private static final float ACTION_SIZE_DP = 56f;
    private static final float ACTION_ICON_DP = 24f;
    private static final float ACTION_GAP_DP = 8f;
    private static final float PILL_HEIGHT_DP = 60f;

    private MorphingNavPill pill;
    private final float density;

    @Nullable
    private ViewPager pager;
    private boolean pagerDriving = false;
    private boolean fakeDragging = false;
    /** Offscreen page limit to go back to after a scrub, or -1 when it was not raised. */
    private int savedOffscreenLimit = -1;
    private boolean ignorePageSelected = false;
    private float lastScrubPosition;
    private int lastReported = -1;
    private int scrubStartIndex = 0;
    private int hoveredAction = -1;
    private final java.util.ArrayList<Action> actionList = new java.util.ArrayList<>();
    @Nullable
    private OnItemSelectedListener listener;

    private final ViewPager.OnPageChangeListener pagerListener =
            new ViewPager.SimpleOnPageChangeListener() {
                @Override
                public void onPageScrolled(final int position, final float positionOffset,
                                           final int positionOffsetPixels) {
                    // only follow the pager while the user drags it (or it flings on): a tap
                    // runs its own spring animation
                    if (pagerDriving) {
                        pill.setPagerProgress(position, positionOffset);
                    }
                }

                @Override
                public void onPageSelected(final int position) {
                    if (ignorePageSelected) {
                        return;
                    }
                    if (!pagerDriving) {
                        pill.setSelectedIndex(position, true);
                    }
                    report(position);
                }

                @Override
                public void onPageScrollStateChanged(final int state) {
                    if (state == ViewPager.SCROLL_STATE_IDLE) {
                        restoreOffscreenLimit();
                    }
                    if (state == ViewPager.SCROLL_STATE_DRAGGING) {
                        pagerDriving = true;
                    } else if (state == ViewPager.SCROLL_STATE_IDLE && pagerDriving) {
                        pagerDriving = false;
                        if (pager != null) {
                            pill.settleAt(pager.getCurrentItem());
                        }
                    }
                }
            };

    public FloatingNavBar(@NonNull final Context context) {
        this(context, null);
    }

    public FloatingNavBar(@NonNull final Context context, @Nullable final AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;

        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        // the elevation shadows of the pill and the buttons are drawn outside of their bounds
        setClipChildren(false);
        setClipToPadding(false);

        pill = new MorphingNavPill(context);
        pill.setOnItemClickListener(new MorphingNavPill.OnItemClickListener() {
            @Override
            public void onItemClicked(final int index) {
                // slide the pages right away, the pill is morphing to the tab already
                pagerDriving = false;
                if (pager != null) {
                    pager.setCurrentItem(index, true);
                }
                report(index);
            }

            @Override
            public void onItemReselected(final int index) {
                if (listener != null) {
                    listener.onItemReselected(index);
                }
            }
        });
        pill.setOnScrubListener(new MorphingNavPill.OnScrubListener() {
            @Override
            public boolean onScrubStart(final float position) {
                lastScrubPosition = position;
                scrubStartIndex = pill.getSelectedIndex();
                setHoveredAction(-1);
                raiseOffscreenLimit();
                if (pager != null && pager.getAdapter() != null && pager.beginFakeDrag()) {
                    fakeDragging = true;
                }
                // true: the pager moves under the finger and the pill just follows the pager
                return fakeDragging;
            }

            @Override
            public void onScrub(final float position) {
                // the pages move exactly as far as the finger moved over the pill
                if (fakeDragging && pager != null) {
                    final int pageWidth = pager.getWidth() - pager.getPaddingLeft()
                            - pager.getPaddingRight();
                    pager.fakeDragBy(-(position - lastScrubPosition) * pageWidth);
                }
                lastScrubPosition = position;
            }

            @Override
            public void onScrubRaw(final float xInPill) {
                // the finger can be dragged past the last tab onto a round button
                // (downloads / history): that button lights up and opens on release
                final float xInBar = xInPill + pill.getLeft();
                int found = -1;
                for (int i = 0; i < actionList.size(); i++) {
                    final View button = getChildAt(i + 1);
                    if (button != null && xInBar >= button.getLeft() - ACTION_GAP_DP * density / 2f
                            && xInBar <= button.getRight() + ACTION_GAP_DP * density / 2f) {
                        found = i;
                        break;
                    }
                }
                setHoveredAction(found);
            }

            @Override
            public void onScrubEnd(final int target) {
                // the pill springs by itself now, so it must not follow the pager any more
                pagerDriving = false;
                final int action = hoveredAction;
                setHoveredAction(-1);
                if (action >= 0 && action < actionList.size()) {
                    ignorePageSelected = true;
                    endFakeDrag();
                    ignorePageSelected = false;
                    // go back to the tab we came from, then open the button's screen
                    pill.setSelectedIndex(scrubStartIndex, true);
                    if (pager != null) {
                        pager.setCurrentItem(scrubStartIndex, true);
                    }
                    actionList.get(action).onClick.run();
                    return;
                }
                // ViewPager picks a page of its own when the fake drag ends; ignore that and go
                // to the page the pill snapped to (past the 50 % mark, else back)
                ignorePageSelected = true;
                endFakeDrag();
                if (pager != null) {
                    pager.setCurrentItem(target, true);
                }
                ignorePageSelected = false;
                report(target);
            }
        });
        addView(pill, new LayoutParams(0, Math.round(PILL_HEIGHT_DP * density), 1f));
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Public API
    //////////////////////////////////////////////////////////////////////////*/

    public void setOnItemSelectedListener(@Nullable final OnItemSelectedListener l) {
        listener = l;
    }

    public void setItems(@NonNull final List<MorphingNavPill.Item> items) {
        pill.setItems(items);
        lastReported = pill.getSelectedIndex();
        pill.setVisibility(items.size() > 1 ? VISIBLE : GONE);
        updateVisibility();
    }

    public int getSelectedIndex() {
        return pill.getSelectedIndex();
    }

    public void setSelectedIndex(final int index, final boolean animate) {
        pill.setSelectedIndex(index, animate);
        lastReported = index;
    }

    /** Accent colour of the selected chip. */
    public void setChipColor(final int color) {
        pill.setChipColor(color);
    }

    /** Replaces the round buttons that sit to the right of the pill. */
    public void setActions(@NonNull final List<Action> actions) {
        // everything after the pill is an action button
        removeViews(1, getChildCount() - 1);
        actionList.clear();
        actionList.addAll(actions);
        hoveredAction = -1;

        final int size = Math.round(ACTION_SIZE_DP * density);
        final int padding = Math.round((ACTION_SIZE_DP - ACTION_ICON_DP) / 2f * density);
        for (final Action action : actions) {
            final ImageView button = new ImageView(getContext());
            button.setImageDrawable(AppCompatResources.getDrawable(getContext(), action.iconRes));
            button.setImageTintList(ColorStateList.valueOf(
                    ThemeHelper.resolveColorFromAttr(getContext(),
                            android.R.attr.textColorPrimary)));
            button.setScaleType(ImageView.ScaleType.FIT_CENTER);
            button.setPadding(padding, padding, padding, padding);
            button.setBackgroundResource(R.drawable.bg_nav_action);
            button.setClipToOutline(true);
            button.setElevation(10f * density);
            button.setContentDescription(action.description);
            button.setClickable(true);
            button.setFocusable(true);
            button.setStateListAnimator(AnimatorInflater.loadStateListAnimator(getContext(),
                    R.animator.press_scale));
            button.setOnClickListener((View v) -> action.onClick.run());

            final LayoutParams params = new LayoutParams(size, size);
            params.setMarginStart(Math.round(ACTION_GAP_DP * density));
            addView(button, params);
        }
        updateVisibility();
    }

    /**
     * Makes the pages slide and the bar morph together: a tap on a tab slides the pages (and
     * morphs the pill with a spring), and dragging the pages morphs the pill under the finger.
     */
    public void attachToViewPager(@NonNull final ViewPager viewPager) {
        if (pager != null) {
            pager.removeOnPageChangeListener(pagerListener);
        }
        pager = viewPager;
        pagerDriving = false;
        viewPager.addOnPageChangeListener(pagerListener);
    }

    public void detachFromViewPager() {
        if (pager != null) {
            pager.removeOnPageChangeListener(pagerListener);
            pager = null;
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Internals
    //////////////////////////////////////////////////////////////////////////*/

    private void setHoveredAction(final int index) {
        if (index == hoveredAction) {
            return;
        }
        hoveredAction = index;
        for (int i = 0; i < actionList.size(); i++) {
            final View button = getChildAt(i + 1);
            if (button != null) {
                final boolean on = i == index;
                button.animate().scaleX(on ? 1.2f : 1f).scaleY(on ? 1.2f : 1f)
                        .setDuration(120).start();
                button.setSelected(on);
                if (button instanceof ImageView) {
                    ((ImageView) button).setImageTintList(ColorStateList.valueOf(
                            ThemeHelper.resolveColorFromAttr(getContext(), on
                                    ? R.attr.toolbar_container_color
                                    : android.R.attr.textColorPrimary)));
                }
            }
        }
        if (index >= 0) {
            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
        }
    }

    /**
     * A ViewPager only lets itself be dragged as far as the pages it has loaded: the current
     * one and (by default) one on each side. To scrub over ALL the tabs, every page is loaded
     * while the finger is on the pill; {@link #restoreOffscreenLimit()} undoes it afterwards.
     */
    private void raiseOffscreenLimit() {
        if (pager == null || pager.getAdapter() == null || savedOffscreenLimit >= 0) {
            return;
        }
        final int needed = pager.getAdapter().getCount() - 1;
        if (needed > pager.getOffscreenPageLimit()) {
            savedOffscreenLimit = pager.getOffscreenPageLimit();
            pager.setOffscreenPageLimit(needed);
        }
    }

    private void restoreOffscreenLimit() {
        if (savedOffscreenLimit >= 0 && pager != null) {
            final int limit = savedOffscreenLimit;
            savedOffscreenLimit = -1;
            pager.setOffscreenPageLimit(limit);
        }
    }

    private void endFakeDrag() {
        if (fakeDragging && pager != null) {
            fakeDragging = false;
            try {
                pager.endFakeDrag();
            } catch (final IllegalStateException ignored) {
                // the drag was already ended
            }
        }
        fakeDragging = false;
    }

    private void report(final int index) {
        if (index != lastReported) {
            lastReported = index;
            if (listener != null) {
                listener.onItemSelected(index);
            }
        }
    }

    /** The whole bar is hidden when there is neither a pill (less than 2 tabs) nor a button. */
    private void updateVisibility() {
        final boolean any = pill.getVisibility() == VISIBLE || getChildCount() > 1;
        setVisibility(any ? VISIBLE : GONE);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (pager != null) {
            pager.removeOnPageChangeListener(pagerListener);
            pager.addOnPageChangeListener(pagerListener);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (pager != null) {
            pager.removeOnPageChangeListener(pagerListener);
        }
        pagerDriving = false;
        endFakeDrag();
        restoreOffscreenLimit();
    }
}
