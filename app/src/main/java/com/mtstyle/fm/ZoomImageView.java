package com.mtstyle.fm;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

/**
 * 可缩放/拖动的图片视图（图片查看器核心）：
 * - 双指捏合缩放，双击在「适应屏幕 / 2.5 倍」间切换；
 * - 放大后单指拖动平移，边界自动吸附；
 * - 未放大时左右快速滑动触发上一张/下一张。
 */
public class ZoomImageView extends AppCompatImageView {

    /** 缩放手势与切换回调。 */
    public interface Listener {
        /** 单击（用于显示/隐藏信息栏）。 */
        void onSingleTap();

        /** 未放大时左右滑动切换图片：delta < 0 为下一张，> 0 为上一张。 */
        void onSwipe(int delta);
    }

    private static final float MAX_SCALE = 8f;
    private static final float DOUBLE_TAP_SCALE = 2.5f;

    private final Matrix matrix = new Matrix();
    private final float[] values = new float[9];
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;

    /** 图片适应屏幕时的基础缩放。 */
    private float baseScale = 1f;
    /** 用户额外放大的倍数（1 = 适应屏幕）。 */
    private float userScale = 1f;
    private float lastX;
    private float lastY;
    private boolean dragging;
    @Nullable
    private Listener listener;

    public ZoomImageView(Context context) {
        this(context, null);
    }

    public ZoomImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setScaleType(ScaleType.MATRIX);
        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        float target = baseScale * userScale * detector.getScaleFactor();
                        applyScale(target, detector.getFocusX(), detector.getFocusY());
                        return true;
                    }
                });
        gestureDetector = new GestureDetector(context,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(MotionEvent e) {
                        return true;
                    }

                    @Override
                    public boolean onSingleTapConfirmed(MotionEvent e) {
                        if (listener != null) {
                            listener.onSingleTap();
                        }
                        return true;
                    }

                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        if (userScale > 1.05f) {
                            resetZoom();
                        } else {
                            applyScale(baseScale * DOUBLE_TAP_SCALE, e.getX(), e.getY());
                        }
                        return true;
                    }

                    @Override
                    public boolean onFling(MotionEvent e1, MotionEvent e2,
                                           float velocityX, float velocityY) {
                        if (e1 == null || e2 == null || userScale > 1.05f) {
                            return false;
                        }
                        float dx = e2.getX() - e1.getX();
                        float dy = e2.getY() - e1.getY();
                        if (Math.abs(dx) < 60 || Math.abs(dx) < Math.abs(dy)
                                || Math.abs(velocityX) < 300) {
                            return false;
                        }
                        if (listener != null) {
                            listener.onSwipe(dx < 0 ? 1 : -1);
                        }
                        return true;
                    }
                });
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    @Override
    public void setImageDrawable(@Nullable Drawable drawable) {
        super.setImageDrawable(drawable);
        post(this::fitToView);
    }

    @Override
    public void setImageBitmap(android.graphics.Bitmap bm) {
        super.setImageBitmap(bm);
        post(this::fitToView);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fitToView();
    }

    /** 让图片完整居中显示（适应屏幕）。 */
    public void fitToView() {
        Drawable drawable = getDrawable();
        int viewWidth = getWidth();
        int viewHeight = getHeight();
        if (drawable == null || viewWidth <= 0 || viewHeight <= 0) {
            return;
        }
        float drawableWidth = drawable.getIntrinsicWidth();
        float drawableHeight = drawable.getIntrinsicHeight();
        if (drawableWidth <= 0 || drawableHeight <= 0) {
            return;
        }
        baseScale = Math.min(viewWidth / drawableWidth, viewHeight / drawableHeight);
        userScale = 1f;
        matrix.reset();
        matrix.postScale(baseScale, baseScale);
        matrix.postTranslate((viewWidth - drawableWidth * baseScale) / 2f,
                (viewHeight - drawableHeight * baseScale) / 2f);
        setImageMatrix(matrix);
    }

    /** 恢复适应屏幕状态。 */
    public void resetZoom() {
        if (getDrawable() == null) {
            return;
        }
        if (userScale <= 1.02f) {
            fitToView();
            return;
        }
        applyScale(baseScale, getWidth() / 2f, getHeight() / 2f);
    }

    /** 以 (focusX, focusY) 为焦点缩放到 targetScale（绝对缩放，含 baseScale）。 */
    private void applyScale(float targetScale, float focusX, float focusY) {
        Drawable drawable = getDrawable();
        if (drawable == null) {
            return;
        }
        float minScale = baseScale;
        float maxScale = baseScale * MAX_SCALE;
        float clamped = Math.max(minScale, Math.min(maxScale, targetScale));
        float current = baseScale * userScale;
        if (current <= 0f) {
            return;
        }
        float factor = clamped / current;
        matrix.postScale(factor, factor, focusX, focusY);
        userScale = clamped / baseScale;
        if (userScale < 1.02f) {
            userScale = 1f;
            fitToView();
            return;
        }
        clampTranslation();
        setImageMatrix(matrix);
    }

    /** 拖动/缩放后把图片限制在视图范围内（比视图小的方向居中）。 */
    private void clampTranslation() {
        Drawable drawable = getDrawable();
        if (drawable == null) {
            return;
        }
        matrix.getValues(values);
        float scale = values[Matrix.MSCALE_X];
        float transX = values[Matrix.MTRANS_X];
        float transY = values[Matrix.MTRANS_Y];
        float scaledWidth = drawable.getIntrinsicWidth() * scale;
        float scaledHeight = drawable.getIntrinsicHeight() * scale;
        float viewWidth = getWidth();
        float viewHeight = getHeight();

        float minX;
        float maxX;
        if (scaledWidth <= viewWidth) {
            minX = maxX = (viewWidth - scaledWidth) / 2f;
        } else {
            maxX = 0f;
            minX = viewWidth - scaledWidth;
        }
        float minY;
        float maxY;
        if (scaledHeight <= viewHeight) {
            minY = maxY = (viewHeight - scaledHeight) / 2f;
        } else {
            maxY = 0f;
            minY = viewHeight - scaledHeight;
        }
        float fixedX = Math.max(minX, Math.min(maxX, transX));
        float fixedY = Math.max(minY, Math.min(maxY, transY));
        if (fixedX != transX || fixedY != transY) {
            matrix.postTranslate(fixedX - transX, fixedY - transY);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = event.getX();
                lastY = event.getY();
                dragging = true;
                break;
            case MotionEvent.ACTION_MOVE:
                if (dragging && !scaleDetector.isInProgress() && userScale > 1.02f) {
                    float dx = event.getX() - lastX;
                    float dy = event.getY() - lastY;
                    matrix.postTranslate(dx, dy);
                    clampTranslation();
                    setImageMatrix(matrix);
                    lastX = event.getX();
                    lastY = event.getY();
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                break;
            default:
                break;
        }
        return true;
    }
}
