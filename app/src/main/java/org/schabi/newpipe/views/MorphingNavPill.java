package org.schabi.newpipe.views;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.Interpolator;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import org.schabi.newpipe.R;
import org.schabi.newpipe.util.ThemeHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * The capsule of the floating bottom navigation bar, drawn like the nav pill of the Shrinky app:
 * a card coloured pill with a soft shadow, text-only labels, and a soft "thumb" that slides
 * behind the labels. The label nearest to the thumb turns accent red, and the colour fades
 * between neighbours while the thumb is in between.
 *
 * <p>The whole pill is driven by ONE float, {@link #position}: the (fractional) tab index.
 * The thumb sits at {@code position * segmentWidth} and every label's colour is derived from
 * its distance to {@code position}. The position comes from one of two sources:</p>
 * <ul>
 *   <li>the ViewPager (see {@link #setPagerProgress(int, float)}): the thumb always follows the
 *   pages, whether the user swipes them, taps a tab or drags the pill, or</li>
 *   <li>a short critically damped glide ({@link #setSelectedIndex}), used only when no pager
 *   is attached.</li>
 * </ul>
 *
 * <p>Dragging the pill sideways moves the pages by exactly the distance the finger travelled in
 * "tabs" (see {@link OnScrubListener}); on release the pill goes to the nearest tab.</p>
 */
public class MorphingNavPill extends View {
    /** One tab of the pill. The icon is kept for API compatibility; only the label is drawn. */
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
        /** A tab that is not selected yet was tapped. */
        void onItemClicked(int index);

        /** The tab that is already selected was tapped again. */
        void onItemReselected(int index);
    }

    /** Reports a finger that is dragged over the pill (scrubbing through the tabs). */
    public interface OnScrubListener {
        /**
         * @param position the (fractional) tab index the pill is at when the drag starts
         * @return true when something else (the ViewPager) now drives the pill through
         *         {@link #setPagerProgress}; the pill then only reports finger movement
         */
        boolean onScrubStart(float position);

        /** @param position the (fractional) tab index under the drag, 0..count-1 */
        void onScrub(float position);

        /** The finger was lifted: the pill goes to {@code target}. */
        void onScrubEnd(int target);

        /** Raw finger x in this view's coordinates; may lie outside the pill (over buttons). */
        default void onScrubRaw(final float xInPill) { }
    }

    private static final float PILL_PADDING_DP = 4f;
    private static final float LABEL_SIZE_SP = 16f;
    private static final float LABEL_H_PADDING_DP = 6f;
    private static final float DEFAULT_HEIGHT_DP = 56f;
    private static final long GLIDE_MS = 450L;

    /** Critically damped spring (stiffness 350, damping ratio 1): glides, never bounces. */
    static final Interpolator GLIDE = new Interpolator() {
        private final float omega = (float) Math.sqrt(350.0);
        private final float end = raw(GLIDE_MS / 1000f);

        private float raw(final float seconds) {
            final float x = omega * seconds;
            return 1f - (1f + x) * (float) Math.exp(-x);
        }

        @Override
        public float getInterpolation(final float t) {
            if (t <= 0f) {
                return 0f;
            }
            if (t >= 1f) {
                return 1f;
            }
            return raw(t * GLIDE_MS / 1000f) / end;
        }
    };

    private final List<CharSequence> labels = new ArrayList<>();
    private final List<CharSequence> shownLabels = new ArrayList<>();
    private final TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF thumbRect = new RectF();

    private final float density;
    private final int padPx;
    private final int touchSlop;
    private final int textColor;
    private int accentColor;

    private float position = 0f;
    private int selectedIndex = -1;
    @Nullable
    private ValueAnimator animator;
    @Nullable
    private OnItemClickListener clickListener;
    @Nullable
    private OnScrubListener scrubListener;

    private boolean tracking = false;
    private boolean scrubbing = false;
    private boolean externalDrive = false;
    private float downX;
    private float startPosition;
    private float lastPosition;
    private int lastTick;

    public MorphingNavPill(@NonNull final Context context) {
        this(context, null);
    }

    public MorphingNavPill(@NonNull final Context context, @Nullable final AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        padPx = Math.round(PILL_PADDING_DP * density);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        textColor = ThemeHelper.resolveColorFromAttr(context, android.R.attr.textColorPrimary);
        accentColor = ContextCompat.getColor(context, R.color.nothing_red);
        thumbPaint.setColor(ThemeHelper.resolveColorFromAttr(context,
                R.attr.card_item_contrast_color));

        labelPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                LABEL_SIZE_SP, context.getResources().getDisplayMetrics()));
        labelPaint.setTextAlign(Paint.Align.CENTER);
        final Typeface geist = ResourcesCompat.getFont(context, R.font.geist_regular);
        if (geist != null) {
            labelPaint.setTypeface(geist);
        }

        // card surface, no outline: the soft shadow lifts the pill off the page
        setBackgroundResource(R.drawable.bg_nav_pill);
        setClipToOutline(true);
        setElevation(6f * density);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            setOutlineAmbientShadowColor(0x33000000);
            setOutlineSpotShadowColor(0x33000000);
        }
        setClickable(true);
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
        labels.clear();
        for (final Item item : items) {
            labels.add(item.label);
        }
        selectedIndex = labels.isEmpty() ? -1 : 0;
        position = 0f;
        rebuildShownLabels();
        invalidate();
    }

    /** Colour of the active label (default: Nothing red). */
    public void setChipColor(final int color) {
        accentColor = color;
        invalidate();
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    public int getItemCount() {
        return labels.size();
    }

    /**
     * Selects a tab without reporting it to the click listener.
     *
     * @param index   tab to select
     * @param animate true to glide there, false to jump
     */
    public void setSelectedIndex(final int index, final boolean animate) {
        if (index < 0 || index >= labels.size()) {
            return;
        }
        if (index == selectedIndex && !isAnimating() && position == index) {
            return;
        }
        selectedIndex = index;
        if (animate && isLaidOut()) {
            glideTo(index);
        } else {
            cancelAnimator();
            position = index;
            invalidate();
        }
    }

    /**
     * Only remembers which tab is the selected one. The thumb is NOT moved: the pager that
     * is scrolling to that page moves it, frame by frame, through {@link #setPagerProgress}.
     */
    public void markSelected(final int index) {
        if (index >= 0 && index < labels.size()) {
            selectedIndex = index;
        }
    }

    /**
     * Follows a ViewPager: the thumb is at {@code position + offset}.
     *
     * @param pagerPosition the page on the left, as in {@code onPageScrolled}
     * @param offset        how far the next page has slid in, 0..1
     */
    public void setPagerProgress(final int pagerPosition, final float offset) {
        final int count = labels.size();
        if (count == 0 || pagerPosition < 0 || pagerPosition >= count) {
            return;
        }
        cancelAnimator();
        position = Math.min(count - 1f, pagerPosition + offset);
        invalidate();
    }

    /** Ends a pager driven move: the thumb rests exactly on the page the pager came to. */
    public void settleAt(final int index) {
        if (index < 0 || index >= labels.size()) {
            return;
        }
        selectedIndex = index;
        cancelAnimator();
        position = index;
        invalidate();
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Glide (only used when no pager drives the thumb)
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

    private void glideTo(final int index) {
        cancelAnimator();
        final float from = position;
        final ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(GLIDE_MS);
        anim.setInterpolator(GLIDE);
        anim.addUpdateListener(a -> {
            position = from + (index - from) * (Float) a.getAnimatedValue();
            invalidate();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(final Animator animation) {
                if (animator == anim) {
                    animator = null;
                    position = index;
                    invalidate();
                }
            }
        });
        animator = anim;
        anim.start();
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Touch: tap a label, or hold the pill and drag sideways
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public boolean onTouchEvent(final MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                stopTracking();
                tracking = true;
                downX = ev.getX();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!tracking) {
                    return false;
                }
                if (!scrubbing && Math.abs(ev.getX() - downX) > touchSlop) {
                    startScrub(ev.getX());
                }
                if (scrubbing) {
                    updateScrub(ev.getX());
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (scrubbing) {
                    finishScrub();
                } else if (tracking) {
                    onTap(ev.getX());
                }
                stopTracking();
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (scrubbing) {
                    finishScrub();
                }
                stopTracking();
                return true;
            default:
                return true;
        }
    }

    private void stopTracking() {
        tracking = false;
        scrubbing = false;
        externalDrive = false;
    }

    private float segmentWidth() {
        final int count = labels.size();
        return count == 0 ? 0f : (getWidth() - 2f * padPx) / count;
    }

    private void onTap(final float x) {
        final int count = labels.size();
        final float seg = segmentWidth();
        if (count == 0 || seg <= 0f) {
            return;
        }
        final int index = Math.max(0, Math.min(count - 1, (int) ((x - padPx) / seg)));
        performClick();
        if (index == selectedIndex) {
            if (clickListener != null) {
                clickListener.onItemReselected(index);
            }
            return;
        }
        selectedIndex = index;
        if (clickListener != null) {
            clickListener.onItemClicked(index);
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void startScrub(final float x) {
        if (labels.size() < 2) {
            return;
        }
        scrubbing = true;
        cancelAnimator();
        // the drag is relative to where the finger crossed the slop, so nothing jumps
        downX = x;
        startPosition = position;
        lastPosition = startPosition;
        lastTick = Math.round(startPosition);
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        externalDrive = scrubListener != null && scrubListener.onScrubStart(startPosition);
    }

    private void updateScrub(final float x) {
        final int count = labels.size();
        final float seg = segmentWidth();
        if (count < 2 || seg <= 0f) {
            return;
        }
        final float scrubPosition = Math.max(0f, Math.min(count - 1f,
                startPosition + (x - downX) / seg));
        lastPosition = scrubPosition;

        if (!externalDrive) {
            position = scrubPosition;
            invalidate();
        }

        final int tick = Math.round(scrubPosition);
        if (tick != lastTick) {
            lastTick = tick;
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
        if (scrubListener != null) {
            scrubListener.onScrub(scrubPosition);
            scrubListener.onScrubRaw(x);
        }
    }

    private void finishScrub() {
        final int count = labels.size();
        // past the 50 % midpoint towards a neighbour the pill goes to that tab,
        // before it the pill goes back to the tab it was dragged from
        final float released = externalDrive ? position : lastPosition;
        final int target = Math.max(0, Math.min(count - 1, Math.round(released)));

        final boolean driven = externalDrive;
        scrubbing = false;
        externalDrive = false;
        selectedIndex = target;
        if (!driven) {
            glideTo(target);
        }
        if (scrubListener != null) {
            scrubListener.onScrubEnd(target);
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Drawing
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec) {
        final int width = MeasureSpec.getSize(widthMeasureSpec);
        final int height = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED
                ? Math.round(DEFAULT_HEIGHT_DP * density) : MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onSizeChanged(final int w, final int h, final int oldw, final int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rebuildShownLabels();
    }

    /** A label longer than its segment is cut with an ellipsis, never drawn over a neighbour. */
    private void rebuildShownLabels() {
        shownLabels.clear();
        final float room = Math.max(0f, segmentWidth() - 2f * LABEL_H_PADDING_DP * density);
        for (final CharSequence label : labels) {
            shownLabels.add(room <= 0f ? label
                    : TextUtils.ellipsize(label, labelPaint, room, TextUtils.TruncateAt.END));
        }
    }

    @Override
    protected void onDraw(@NonNull final Canvas canvas) {
        final int count = labels.size();
        final float seg = segmentWidth();
        if (count == 0 || seg <= 0f) {
            return;
        }
        final int h = getHeight();

        // the thumb: one segment wide, sliding behind the labels
        final float left = padPx + position * seg;
        thumbRect.set(left, padPx, left + seg, h - padPx);
        canvas.drawRoundRect(thumbRect, thumbRect.height() / 2f, thumbRect.height() / 2f,
                thumbPaint);

        final Paint.FontMetrics fm = labelPaint.getFontMetrics();
        final float baseline = h / 2f - (fm.ascent + fm.descent) / 2f;
        for (int i = 0; i < count && i < shownLabels.size(); i++) {
            final float near = Math.max(0f, Math.min(1f, 1f - Math.abs(position - i)));
            labelPaint.setColor(lerpColor(textColor, accentColor, near));
            final CharSequence text = shownLabels.get(i);
            canvas.drawText(text, 0, text.length(), padPx + seg * (i + 0.5f), baseline,
                    labelPaint);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancelAnimator();
        stopTracking();
        if (selectedIndex >= 0 && selectedIndex < labels.size()) {
            position = selectedIndex;
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Colour blend in Oklab, the same way Compose's lerp(Color, Color, f) does it
    //////////////////////////////////////////////////////////////////////////*/

    private static int lerpColor(final int from, final int to, final float f) {
        if (f <= 0f) {
            return from;
        }
        if (f >= 1f) {
            return to;
        }
        final float[] a = toOklab(from);
        final float[] b = toOklab(to);
        final float alpha = ((from >>> 24) + (((to >>> 24) - (from >>> 24)) * f)) / 255f;
        return fromOklab(a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f,
                a[2] + (b[2] - a[2]) * f, alpha);
    }

    private static float toLinear(final int channel) {
        final float c = channel / 255f;
        return c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055f) / 1.055f, 2.4f);
    }

    private static int fromLinear(final float linear) {
        final float c = linear <= 0.0031308f ? linear * 12.92f
                : 1.055f * (float) Math.pow(linear, 1f / 2.4f) - 0.055f;
        return Math.max(0, Math.min(255, Math.round(c * 255f)));
    }

    private static float[] toOklab(final int argb) {
        final float r = toLinear((argb >> 16) & 0xFF);
        final float g = toLinear((argb >> 8) & 0xFF);
        final float b = toLinear(argb & 0xFF);
        final float l = (float) Math.cbrt(0.4122214708f * r + 0.5363325363f * g
                + 0.0514459929f * b);
        final float m = (float) Math.cbrt(0.2119034982f * r + 0.6806995451f * g
                + 0.1073969566f * b);
        final float s = (float) Math.cbrt(0.0883024619f * r + 0.2817188376f * g
                + 0.6299787005f * b);
        return new float[] {
                0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s,
                1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s,
                0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s,
        };
    }

    private static int fromOklab(final float lightness, final float a, final float b,
                                 final float alpha) {
        final float l = cube(lightness + 0.3963377774f * a + 0.2158037573f * b);
        final float m = cube(lightness - 0.1055613458f * a - 0.0638541728f * b);
        final float s = cube(lightness - 0.0894841775f * a - 1.2914855480f * b);
        final int r = fromLinear(4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s);
        final int g = fromLinear(-1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s);
        final int bl = fromLinear(-0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s);
        final int al = Math.max(0, Math.min(255, Math.round(alpha * 255f)));
        return (al << 24) | (r << 16) | (g << 8) | bl;
    }

    private static float cube(final float v) {
        return v * v * v;
    }
}
