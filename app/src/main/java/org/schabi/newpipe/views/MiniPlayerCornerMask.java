package org.schabi.newpipe.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Draws the mini player card (in the card's own colour) with a rounded "hole" where the video
 * is. Placed above the video surface and below the mini player buttons, it gives the video
 * smooth rounded corners (a SurfaceView can't be clipped reliably by its parents).
 * It never takes touches.
 */
public class MiniPlayerCornerMask extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path cornerPath = new Path();
    private final Path path = new Path();
    private final RectF card = new RectF();
    private final RectF hole = new RectF();
    private float cardRadius;
    private float holeLeftRadius;
    private float holeRightRadius;
    private final float[] holeRadii = new float[8];
    private boolean hasHole = false;

    public MiniPlayerCornerMask(@NonNull final Context context) {
        super(context);
        init();
    }

    public MiniPlayerCornerMask(@NonNull final Context context,
                                @Nullable final AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        paint.setStyle(Paint.Style.FILL);
        setClickable(false);
        setFocusable(false);
    }

    public void setColor(final int color) {
        paint.setColor(color);
        invalidate();
    }

    /** Colour of the page behind the card, used to cut the video's square corners. */
    public void setBackdropColor(final int color) {
        cornerPaint.setColor(color);
        invalidate();
    }

    public void setGeometry(final float cardLeft, final float cardTop, final float cardRight,
                            final float cardBottom, final float cardCornerRadius,
                            final float videoLeft, final float videoTop,
                            final float videoRight, final float videoBottom,
                            final float videoLeftRadius, final float videoRightRadius) {
        card.set(cardLeft, cardTop, cardRight, cardBottom);
        hole.set(videoLeft, videoTop, videoRight, videoBottom);
        cardRadius = cardCornerRadius;
        holeLeftRadius = videoLeftRadius;
        holeRightRadius = videoRightRadius;
        hasHole = true;
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull final Canvas canvas) {
        if (!hasHole) {
            return;
        }
        // The video sits flush with the card, so its square corners would stick out past the
        // card's rounded corners (video corner radius == card radius, so the hole there cancels
        // the card). Inside the video's bounds, paint everything outside the rounded card in the
        // page colour to cut those corners off.
        cornerPath.reset();
        cornerPath.setFillType(Path.FillType.EVEN_ODD);
        cornerPath.addRect(hole, Path.Direction.CW);
        cornerPath.addRoundRect(card, cardRadius, cardRadius, Path.Direction.CW);
        canvas.save();
        canvas.clipRect(hole);
        canvas.drawPath(cornerPath, cornerPaint);
        canvas.restore();

        path.reset();
        path.setFillType(Path.FillType.EVEN_ODD);
        path.addRoundRect(card, cardRadius, cardRadius, Path.Direction.CW);
        // order: top-left, top-right, bottom-right, bottom-left (x and y radius each)
        holeRadii[0] = holeLeftRadius;
        holeRadii[1] = holeLeftRadius;
        holeRadii[2] = holeRightRadius;
        holeRadii[3] = holeRightRadius;
        holeRadii[4] = holeRightRadius;
        holeRadii[5] = holeRightRadius;
        holeRadii[6] = holeLeftRadius;
        holeRadii[7] = holeLeftRadius;
        path.addRoundRect(hole, holeRadii, Path.Direction.CW);
        canvas.drawPath(path, paint);
    }
}
