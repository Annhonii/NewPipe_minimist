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
    private final Path path = new Path();
    private final RectF card = new RectF();
    private final RectF hole = new RectF();
    private float cardRadius;
    private float holeRadius;
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

    public void setGeometry(final float cardLeft, final float cardTop, final float cardRight,
                            final float cardBottom, final float cardCornerRadius,
                            final float videoLeft, final float videoTop,
                            final float videoRight, final float videoBottom,
                            final float videoCornerRadius) {
        card.set(cardLeft, cardTop, cardRight, cardBottom);
        hole.set(videoLeft, videoTop, videoRight, videoBottom);
        cardRadius = cardCornerRadius;
        holeRadius = videoCornerRadius;
        hasHole = true;
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull final Canvas canvas) {
        if (!hasHole) {
            return;
        }
        path.reset();
        path.setFillType(Path.FillType.EVEN_ODD);
        path.addRoundRect(card, cardRadius, cardRadius, Path.Direction.CW);
        path.addRoundRect(hole, holeRadius, holeRadius, Path.Direction.CW);
        canvas.drawPath(path, paint);
    }
}
