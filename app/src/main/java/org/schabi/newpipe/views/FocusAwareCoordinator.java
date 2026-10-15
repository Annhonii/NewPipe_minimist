/*
 * Copyright (C) Eltex ltd 2019 <eltex@eltex-co.ru>
 * FocusAwareCoordinator.java is part of NewPipe.
 *
 * NewPipe is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NewPipe is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NewPipe.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.schabi.newpipe.views;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;

import org.schabi.newpipe.R;

public final class FocusAwareCoordinator extends CoordinatorLayout {
    private final Rect childFocus = new Rect();
    private boolean routingToPage;

    public FocusAwareCoordinator(@NonNull final Context context) {
        super(context);
    }

    public FocusAwareCoordinator(@NonNull final Context context,
                                 @Nullable final AttributeSet attrs) {
        super(context, attrs);
    }

    public FocusAwareCoordinator(@NonNull final Context context,
                                 @Nullable final AttributeSet attrs, final int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    /**
     * While the player is minimized, only its card (at the top of the collapsed sheet) belongs to
     * the player. Touches below the card (the floating navigation pill) go to the page, so tabs
     * can still be switched while the mini player is visible.
     */
    @Override
    public boolean dispatchTouchEvent(final MotionEvent ev) {
        final int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            routingToPage = isBelowMiniPlayerCard(ev);
        }
        if (!routingToPage) {
            return super.dispatchTouchEvent(ev);
        }

        final View page = findViewById(R.id.fragment_holder);
        boolean handled = false;
        if (page != null) {
            final MotionEvent copy = MotionEvent.obtain(ev);
            copy.offsetLocation(-page.getLeft(), -page.getTop());
            handled = page.dispatchTouchEvent(copy);
            copy.recycle();
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            routingToPage = false;
        }
        return handled;
    }

    private boolean isBelowMiniPlayerCard(final MotionEvent ev) {
        final View sheet = findViewById(R.id.fragment_player_holder);
        if (sheet == null || sheet.getVisibility() != View.VISIBLE) {
            return false;
        }
        final var params = sheet.getLayoutParams();
        if (!(params instanceof LayoutParams)
                || !(((LayoutParams) params).getBehavior() instanceof BottomSheetBehavior)) {
            return false;
        }
        final BottomSheetBehavior<?> behavior =
                (BottomSheetBehavior<?>) ((LayoutParams) params).getBehavior();
        if (behavior.getState() != BottomSheetBehavior.STATE_COLLAPSED) {
            return false;
        }
        final int cardBottom = sheet.getTop()
                + getResources().getDimensionPixelSize(R.dimen.mini_player_height);
        return ev.getY() > cardBottom;
    }

    @Override
    public void requestChildFocus(final View child, final View focused) {
        super.requestChildFocus(child, focused);

        if (!isInTouchMode()) {
            if (focused.getHeight() >= getHeight()) {
                focused.getFocusedRect(childFocus);

                ((ViewGroup) child).offsetDescendantRectToMyCoords(focused, childFocus);
            } else {
                focused.getHitRect(childFocus);

                ((ViewGroup) child).offsetDescendantRectToMyCoords((View) focused.getParent(),
                        childFocus);
            }

            requestChildRectangleOnScreen(child, childFocus, false);
        }
    }

    /**
     * Applies window insets to all children, not just for the first who consume the insets.
     * Makes possible for multiple fragments to co-exist. Without this code
     * the first ViewGroup who consumes will be the last who receive the insets
     */
    @Override
    public WindowInsets dispatchApplyWindowInsets(final WindowInsets insets) {
        boolean consumed = false;
        for (int i = 0; i < getChildCount(); i++) {
            final View child = getChildAt(i);
            final WindowInsets res = child.dispatchApplyWindowInsets(insets);
            if (res.isConsumed()) {
                consumed = true;
            }
        }

        return consumed ? WindowInsetsCompat.CONSUMED.toWindowInsets() : insets;
    }

    /**
     * Adjusts player's controls manually because onApplyWindowInsets doesn't work when multiple
     * receivers adjust its bounds. So when two listeners are present (like in profile page)
     * the player's controls will not receive insets. This method fixes it
     */
    @Override
    public WindowInsets onApplyWindowInsets(final WindowInsets windowInsets) {
        final var windowInsetsCompat = WindowInsetsCompat.toWindowInsetsCompat(windowInsets, this);
        final var insets = windowInsetsCompat.getInsets(WindowInsetsCompat.Type.systemBars());
        final ViewGroup controls = findViewById(R.id.playbackControlRoot);
        if (controls != null) {
            controls.setPadding(insets.left, insets.top, insets.right, insets.bottom);
        }
        return super.onApplyWindowInsets(windowInsets);
    }
}
