package org.schabi.newpipe.views;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.Interpolator;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import org.schabi.newpipe.R;
import org.schabi.newpipe.util.ThemeHelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The capsule of the floating bottom navigation bar (Material 3 / Google Phone style).
 *
 * <p>The selected tab is an accent coloured chip showing icon + label, every other tab is just
 * an icon. Each tab owns a "selection fraction": 0 = collapsed icon, 1 = expanded chip. The
 * width, the background colour, the label alpha and the label scale are all derived from that
 * single number, and the widths always add up to the width of the pill, so the pill itself never
 * changes size while the chips morph.</p>
 *
 * <p>The fractions are driven by one of two sources:</p>
 * <ul>
 *   <li>a tap, which runs a short under-damped spring animation (about 300 ms), or</li>
 *   <li>a ViewPager that the user is dragging, so the chip follows the finger exactly and the
 *   page slide and the bar morph are always in sync (see
 *   {@link #setPagerProgress(int, float)}).</li>
 * </ul>
 */
public class MorphingNavPill extends ViewGroup {
    /** One tab of the pill. */
    public static final class Item {
        @DrawableRes
        final int iconRes;
        @NonNull
        final CharSequence label;

        public Item(@DrawableRes final int iconRes, @NonNull final CharSequence label) {
            this.iconRes = iconRes;
            this.label = label;
        }
    }

    /** Receives taps on the tabs. */
    public interface OnItemClickListener {
        /** A tab that is not selected yet was tapped (the pill is already morphing to it). */
        void onItemClicked(int index);

        /** The tab that is already selected was tapped again. */
        void onItemReselected(int index);
    }

    /** Reports a finger that is dragged over the pill ("scrubbing" through the tabs). */
    public interface OnScrubListener {
        /** @param position the (fractional) tab index the pill is at when the drag starts */
        void onScrubStart(float position);

        /** @param position the (fractional) tab index under the drag, 0..count-1 */
        void onScrub(float position);

        /** The finger was lifted: the pill springs to {@code target}. */
        void onScrubEnd(int target);

        /** Raw finger x in this view's coordinates; may lie outside the pill (over buttons). */
        default void onScrubRaw(final float xInPill) { }
    }

    private static final float FLING_VELOCITY = 800f;
    private static final long SELECT_DURATION_MS = 300L;
    private static final float ICON_SIZE_DP = 24f;
    private static final float ICON_LABEL_GAP_DP = 8f;
    private static final float CHIP_H_PADDING_DP = 16f;
    private static final float MIN_COLLAPSED_DP = 40f;
    private static final float PILL_PADDING_DP = 6f;

    /**
     * Under-damped spring step response, so the chip slightly overshoots its final width and
     * settles: damping ratio 0.65 (about 7% overshoot), no visible movement after ~300 ms.
     */
    private static final Interpolator SPRING = new Interpolator() {
        private static final float OMEGA = 15f;
        private static final float ZETA = 0.65f;

        @Override
        public float getInterpolation(final float t) {
            if (t <= 0f) {
                return 0f;
            }
            if (t >= 1f) {
                return 1f;
            }
            final float damped = OMEGA * (float) Math.sqrt(1f - ZETA * ZETA);
            final float decay = (float) Math.exp(-ZETA * OMEGA * t);
            return 1f - decay * ((float) Math.cos(damped * t)
                    + ZETA * OMEGA / damped * (float) Math.sin(damped * t));
        }
    };

    private final List<ItemView> itemViews = new ArrayList<>();
    private float[] fractions = new float[0];
    private int selectedIndex = -1;
    @Nullable
    private ValueAnimator animator;
    @Nullable
    private OnItemClickListener clickListener;

    private int chipColor;
    private final int inactiveColor;

    @Nullable
    private OnScrubListener scrubListener;
    private final int touchSlop;
    private boolean tracking = false;
    private boolean scrubbing = false;
    private float downX;
    private float startPosition;
    private float lastPosition;
    private int lastTick;
    @Nullable
    private VelocityTracker velocity;

    public MorphingNavPill(@NonNull final Context context) {
        this(context, null);
    }

    public MorphingNavPill(@NonNull final Context context, @Nullable final AttributeSet attrs) {
        super(context, attrs);
        final float density = context.getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        inactiveColor = ContextCompat.getColor(context, R.color.nav_inactive);
        chipColor = resolveAccent(context);

        final int padding = Math.round(PILL_PADDING_DP * density);
        setPadding(padding, padding, padding, padding);
        setBackgroundResource(R.drawable.bg_nav_pill);
        setClipChildren(true);
        setClipToOutline(true);
        setElevation(10f * density);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            setOutlineAmbientShadowColor(0x26000000);
            setOutlineSpotShadowColor(0x40000000);
        }
    }

    private static int resolveAccent(@NonNull final Context context) {
        final int accent = ThemeHelper.resolveColorFromAttr(context,
                androidx.appcompat.R.attr.colorAccent);
        if (Color.alpha(accent) == 0) {
            return ThemeHelper.resolveColorFromAttr(context, R.attr.card_item_contrast_color);
        }
        return accent;
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Public API
    //////////////////////////////////////////////////////////////////////////*/

    public void setOnItemClickListener(@Nullable final OnItemClickListener listener) {
        clickListener = listener;
    }

    public void setOnScrubListener(@Nullable final OnScrubListener listener) {
        scrubListener = listener;
    }

    /** Replaces all tabs. The first tab is selected until {@link #setSelectedIndex} is called. */
    public void setItems(@NonNull final List<Item> items) {
        cancelAnimator();
        removeAllViews();
        itemViews.clear();

        for (int i = 0; i < items.size(); i++) {
            final int index = i;
            final ItemView view = new ItemView(getContext(), items.get(i), chipColor,
                    inactiveColor);
            view.setOnClickListener(v -> onItemTapped(index));
            itemViews.add(view);
            addView(view);
        }

        fractions = new float[items.size()];
        selectedIndex = items.isEmpty() ? -1 : 0;
        if (selectedIndex >= 0) {
            fractions[0] = 1f;
        }
        applyFractions();
        requestLayout();
    }

    /** Accent colour of the selected chip. The label / icon colour on it is picked for contrast. */
    public void setChipColor(final int color) {
        chipColor = color;
        for (final ItemView view : itemViews) {
            view.setChipColor(color);
        }
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    public int getItemCount() {
        return itemViews.size();
    }

    /**
     * Selects a tab without reporting it to the click listener.
     *
     * @param index   tab to select
     * @param animate true to morph with the spring animation, false to jump
     */
    public void setSelectedIndex(final int index, final boolean animate) {
        if (index < 0 || index >= itemViews.size()) {
            return;
        }
        // the pager reports the page of a tap again: that must not restart the animation
        if (index == selectedIndex && (isAnimating() || fractions[index] == 1f)) {
            return;
        }
        selectedIndex = index;
        updateSelectedFlags();
        if (animate && isLaidOut()) {
            animateToSelection(index);
        } else {
            cancelAnimator();
            snapToSelection(index);
        }
    }

    /**
     * Follows a ViewPager that is being dragged: the page at {@code position} is shown
     * {@code (1 - offset)} and the next page {@code offset}, and so are the two chips.
     *
     * @param position the page on the left, as in {@code onPageScrolled}
     * @param offset   how far the next page has slid in, 0..1
     */
    public void setPagerProgress(final int position, final float offset) {
        final int count = itemViews.size();
        if (count == 0 || position < 0 || position >= count) {
            return;
        }
        cancelAnimator();
        Arrays.fill(fractions, 0f);
        if (position + 1 < count) {
            fractions[position] = 1f - offset;
            fractions[position + 1] = offset;
        } else {
            fractions[position] = 1f;
        }
        applyFractions();
    }

    /** Ends a pager driven morph: snaps to the page the pager came to rest on. */
    public void settleAt(final int index) {
        if (index < 0 || index >= itemViews.size()) {
            return;
        }
        selectedIndex = index;
        updateSelectedFlags();
        cancelAnimator();
        snapToSelection(index);
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Selection animation
    //////////////////////////////////////////////////////////////////////////*/

    private boolean isAnimating() {
        return animator != null && animator.isRunning();
    }

    private void cancelAnimator() {
        if (animator != null) {
            animator.removeAllListeners();
            animator.cancel();
            animator = null;
        }
    }

    private void snapToSelection(final int index) {
        Arrays.fill(fractions, 0f);
        fractions[index] = 1f;
        applyFractions();
    }

    private void animateToSelection(final int index) {
        cancelAnimator();
        final float[] from = fractions.clone();
        final float[] to = new float[from.length];
        to[index] = 1f;

        final ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(SELECT_DURATION_MS);
        anim.setInterpolator(SPRING);
        anim.addUpdateListener(a -> {
            // the interpolator is applied already, and may go slightly past 1 (the bounce)
            final float s = (Float) a.getAnimatedValue();
            for (int i = 0; i < fractions.length; i++) {
                fractions[i] = from[i] + (to[i] - from[i]) * s;
            }
            applyFractions();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(final Animator animation) {
                if (animator == anim) {
                    animator = null;
                    snapToSelection(index);
                }
            }
        });
        animator = anim;
        anim.start();
    }

    private void updateSelectedFlags() {
        for (int i = 0; i < itemViews.size(); i++) {
            itemViews.get(i).setSelected(i == selectedIndex);
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Scrubbing: hold the pill and drag sideways
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public boolean onInterceptTouchEvent(final MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                beginTracking(ev);
                return false;
            case MotionEvent.ACTION_MOVE:
                if (tracking && velocity != null) {
                    velocity.addMovement(ev);
                    if (!scrubbing && Math.abs(ev.getX() - downX) > touchSlop) {
                        startScrub(ev.getX());
                        return true;
                    }
                }
                return false;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                stopTracking();
                return false;
            default:
                return false;
        }
    }

    @Override
    public boolean onTouchEvent(final MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                beginTracking(ev);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!tracking || velocity == null) {
                    return false;
                }
                velocity.addMovement(ev);
                if (!scrubbing && Math.abs(ev.getX() - downX) > touchSlop) {
                    startScrub(ev.getX());
                }
                if (scrubbing) {
                    updateScrub(ev.getX());
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (velocity != null) {
                    velocity.addMovement(ev);
                }
                if (scrubbing) {
                    finishScrub(true);
                }
                stopTracking();
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (scrubbing) {
                    finishScrub(false);
                }
                stopTracking();
                return true;
            default:
                return true;
        }
    }

    private void beginTracking(final MotionEvent ev) {
        stopTracking();
        tracking = true;
        downX = ev.getX();
        velocity = VelocityTracker.obtain();
        velocity.addMovement(ev);
    }

    private void stopTracking() {
        tracking = false;
        scrubbing = false;
        if (velocity != null) {
            velocity.recycle();
            velocity = null;
        }
    }

    /** The tab index the pill is at right now, fractional while it morphs. */
    private float currentPosition() {
        float position = 0f;
        for (int i = 0; i < fractions.length; i++) {
            position += i * fractions[i];
        }
        return Math.max(0f, Math.min(Math.max(0, fractions.length - 1), position));
    }

    private void startScrub(final float x) {
        if (itemViews.size() < 2) {
            return;
        }
        scrubbing = true;
        cancelAnimator();
        // the drag is relative to where the finger crossed the slop, so nothing jumps
        downX = x;
        startPosition = currentPosition();
        lastPosition = startPosition;
        lastTick = Math.round(startPosition);
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        if (scrubListener != null) {
            scrubListener.onScrubStart(startPosition);
        }
    }

    private void updateScrub(final float x) {
        final int count = itemViews.size();
        final float available = getWidth() - getPaddingLeft() - getPaddingRight();
        if (count < 2 || available <= 0f) {
            return;
        }
        final float position = Math.max(0f, Math.min(count - 1f,
                startPosition + (x - downX) / (available / count)));
        lastPosition = position;

        final int whole = Math.min(count - 1, (int) Math.floor(position));
        setPagerProgress(whole, position - whole);

        final int tick = Math.round(position);
        if (tick != lastTick) {
            lastTick = tick;
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
        if (scrubListener != null) {
            scrubListener.onScrub(position);
            scrubListener.onScrubRaw(x);
        }
    }

    private void finishScrub(final boolean allowFling) {
        final int count = itemViews.size();
        float vx = 0f;
        if (allowFling && velocity != null) {
            velocity.computeCurrentVelocity(1000);
            vx = velocity.getXVelocity();
        }
        // a quick flick moves on to the next tab, otherwise the nearest tab wins
        int target = Math.round(lastPosition);
        if (vx > FLING_VELOCITY) {
            target = Math.max(target, (int) Math.floor(lastPosition) + 1);
        } else if (vx < -FLING_VELOCITY) {
            target = Math.min(target, (int) Math.ceil(lastPosition) - 1);
        }
        target = Math.max(0, Math.min(count - 1, target));

        scrubbing = false;
        setSelectedIndex(target, true);
        if (scrubListener != null) {
            scrubListener.onScrubEnd(target);
        }
    }

    private void onItemTapped(final int index) {
        if (index == selectedIndex) {
            if (clickListener != null) {
                clickListener.onItemReselected(index);
            }
            return;
        }
        setSelectedIndex(index, true);
        if (clickListener != null) {
            clickListener.onItemClicked(index);
        }
    }

    private void applyFractions() {
        for (int i = 0; i < itemViews.size(); i++) {
            itemViews.get(i).setFraction(fractions[i]);
        }
        layoutItems();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancelAnimator();
        stopTracking();
        if (selectedIndex >= 0 && selectedIndex < fractions.length) {
            snapToSelection(selectedIndex);
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Layout: the widths always add up to the width of the pill
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec) {
        final int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        final int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        final float density = getResources().getDisplayMetrics().density;

        int width = widthSize;
        if (widthMode != MeasureSpec.EXACTLY) {
            int widest = 0;
            for (final ItemView view : itemViews) {
                widest = Math.max(widest, view.getNaturalWidth());
            }
            final int count = Math.max(1, itemViews.size());
            final int wanted = getPaddingLeft() + getPaddingRight() + widest
                    + Math.round((count - 1) * MIN_COLLAPSED_DP * density);
            width = widthMode == MeasureSpec.AT_MOST ? Math.min(wanted, widthSize) : wanted;
        }
        final int height = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED
                ? Math.round(60f * density) : MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onLayout(final boolean changed, final int l, final int t, final int r,
                            final int b) {
        layoutItems();
    }

    /**
     * Positions the tabs for the current fractions. Called on every animation frame: the tabs
     * are laid out directly, without a new measure / layout pass of the whole screen.
     *
     * <p>With collapsed width {@code c}, expanded width {@code E_i} and selection {@code f_i}, a
     * tab is {@code c + f_i * (E_i - c)} wide. Choosing {@code c} so that all widths sum up to
     * the available width gives {@code c = (available - sum(f_i * E_i)) / (n - sum(f_i))}.</p>
     */
    private void layoutItems() {
        final int count = itemViews.size();
        final int available = getWidth() - getPaddingLeft() - getPaddingRight();
        final int itemHeight = getHeight() - getPaddingTop() - getPaddingBottom();
        if (count == 0 || available <= 0 || itemHeight <= 0) {
            return;
        }
        final float density = getResources().getDisplayMetrics().density;

        // expanded width of every tab: its natural width, but others keep a minimum width
        final float minCollapsed = MIN_COLLAPSED_DP * density;
        final float maxExpanded = Math.max(minCollapsed, available - (count - 1) * minCollapsed);
        final float[] expanded = new float[count];
        float weighted = 0f;
        float sum = 0f;
        for (int i = 0; i < count; i++) {
            expanded[i] = Math.min(itemViews.get(i).getNaturalWidth(), maxExpanded);
            itemViews.get(i).setExpandedWidth(Math.round(expanded[i]));
            weighted += fractions[i] * expanded[i];
            sum += fractions[i];
        }

        final float collapsed;
        if (count == 1) {
            collapsed = available;
        } else if (count - sum < 0.001f) {
            collapsed = minCollapsed;
        } else {
            collapsed = (available - weighted) / (count - sum);
        }

        // round the edges (not the widths), so the tabs touch each other without gaps
        float cursor = getPaddingLeft();
        int left = Math.round(cursor);
        for (int i = 0; i < count; i++) {
            final float width = count == 1 ? available
                    : collapsed + fractions[i] * (expanded[i] - collapsed);
            cursor += width;
            final int right = i == count - 1
                    ? getPaddingLeft() + available : Math.round(cursor);
            final ItemView view = itemViews.get(i);
            final int top = getPaddingTop();
            view.measure(MeasureSpec.makeMeasureSpec(Math.max(0, right - left),
                            MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(itemHeight, MeasureSpec.EXACTLY));
            view.layout(left, top, Math.max(left, right), top + itemHeight);
            left = right;
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // One tab
    //////////////////////////////////////////////////////////////////////////*/

    /** A tab that draws its chip, icon and label itself from a single selection fraction. */
    private static final class ItemView extends View {
        private static final ArgbEvaluator COLORS = new ArgbEvaluator();

        private final Drawable icon;
        private final CharSequence label;
        private final TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint chipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF chipRect = new RectF();
        private final int inactiveColor;
        private final int iconPx;
        private final int gapPx;
        private final int hPaddingPx;
        private final int naturalLabelWidth;

        private int chipColor;
        private int onChipColor;
        private float fraction;
        private int expandedWidth = -1;
        private CharSequence shownLabel;
        private float shownLabelWidth;

        ItemView(@NonNull final Context context, @NonNull final Item item,
                 final int chipColor, final int inactiveColor) {
            super(context);
            final float density = context.getResources().getDisplayMetrics().density;
            iconPx = Math.round(ICON_SIZE_DP * density);
            gapPx = Math.round(ICON_LABEL_GAP_DP * density);
            hPaddingPx = Math.round(CHIP_H_PADDING_DP * density);
            this.inactiveColor = inactiveColor;
            label = item.label;

            final Drawable drawable = AppCompatResources.getDrawable(context, item.iconRes);
            icon = drawable == null ? null : drawable.mutate();
            if (icon != null) {
                icon.setBounds(0, 0, iconPx, iconPx);
            }

            labelPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f,
                    context.getResources().getDisplayMetrics()));
            labelPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            naturalLabelWidth = Math.round(labelPaint.measureText(label, 0, label.length()));
            shownLabel = label;
            shownLabelWidth = naturalLabelWidth;

            setChipColor(chipColor);
            setContentDescription(label);
            setClickable(true);
            setFocusable(true);

            // soft press feedback, clipped to the (capsule shaped) tab
            final GradientDrawable mask = new GradientDrawable();
            mask.setColor(Color.WHITE);
            mask.setCornerRadius(1000f * density);
            setBackground(new RippleDrawable(ColorStateList.valueOf(0x22888888), null, mask));
        }

        void setChipColor(final int color) {
            chipColor = color;
            onChipColor = ColorUtils.calculateLuminance(color) > 0.5 ? 0xFF111111 : Color.WHITE;
            invalidate();
        }

        void setFraction(final float value) {
            if (fraction != value) {
                fraction = value;
                invalidate();
            }
        }

        /** Width of the tab as a fully expanded chip, with the whole label. */
        int getNaturalWidth() {
            return 2 * hPaddingPx + iconPx + gapPx + naturalLabelWidth;
        }

        /** Width the chip has when expanded. A longer label than fits is cut with an ellipsis. */
        void setExpandedWidth(final int width) {
            if (width == expandedWidth) {
                return;
            }
            expandedWidth = width;
            final int room = Math.max(0, width - 2 * hPaddingPx - iconPx - gapPx);
            if (room >= naturalLabelWidth) {
                shownLabel = label;
                shownLabelWidth = naturalLabelWidth;
            } else {
                shownLabel = TextUtils.ellipsize(label, labelPaint, room,
                        TextUtils.TruncateAt.END);
                shownLabelWidth = labelPaint.measureText(shownLabel, 0, shownLabel.length());
            }
        }

        private static float clamp01(final float v) {
            return Math.max(0f, Math.min(1f, v));
        }

        @Override
        protected void onDraw(@NonNull final Canvas canvas) {
            final int w = getWidth();
            final int h = getHeight();
            // the fraction may overshoot 0..1 (spring), what is drawn must not
            final float fc = clamp01(fraction);

            // accent chip: fades in while the tab widens
            if (fc > 0f) {
                chipPaint.setColor(ColorUtils.setAlphaComponent(chipColor,
                        Math.round(Color.alpha(chipColor) * fc)));
                chipRect.set(0, 0, w, h);
                canvas.drawRoundRect(chipRect, h / 2f, h / 2f, chipPaint);
            }

            // icon + label are centred together, so the icon glides left as the label opens
            final float contentWidth = iconPx + fc * (gapPx + shownLabelWidth);
            final float startX = (w - contentWidth) / 2f;

            if (icon != null) {
                icon.setTint((Integer) COLORS.evaluate(fc, inactiveColor, onChipColor));
                canvas.save();
                canvas.translate(startX, (h - iconPx) / 2f);
                icon.draw(canvas);
                canvas.restore();
            }

            // label: fades in a little after the chip starts to grow, and scales up
            final float labelAlpha = clamp01((fraction - 0.2f) / 0.8f);
            if (labelAlpha > 0f) {
                final float labelX = startX + iconPx + gapPx * fc;
                final float scale = 0.85f + 0.15f * labelAlpha;
                final Paint.FontMetrics fm = labelPaint.getFontMetrics();
                final float baseline = h / 2f - (fm.ascent + fm.descent) / 2f;

                labelPaint.setColor(ColorUtils.setAlphaComponent(onChipColor,
                        Math.round(255 * labelAlpha)));
                canvas.save();
                canvas.clipRect(labelX, 0f, startX + contentWidth, h);
                canvas.scale(scale, scale, labelX, h / 2f);
                canvas.drawText(shownLabel, 0, shownLabel.length(), labelX, baseline,
                        labelPaint);
                canvas.restore();
            }
        }
    }
}
