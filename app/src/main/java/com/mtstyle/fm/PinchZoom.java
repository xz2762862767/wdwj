package com.mtstyle.fm;

import android.content.Context;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.TextView;

/**
 * 双指捏合缩放：只改变字号，单指滚动、文本选择、点击与长按行为保持不变。
 * 查看（TextView）与编辑（EditText）共用。
 */
public final class PinchZoom implements View.OnTouchListener {

    /** 缩放结束/变化回调。 */
    public interface Listener {
        void onScale(float scale, boolean finished);
    }

    public static final float MIN_SCALE = 0.6f;
    public static final float MAX_SCALE = 3.5f;
    public static final float DEFAULT_SCALE = 1f;

    private final TextView target;
    private final ScaleGestureDetector detector;
    private final Listener listener;
    private final float baseSize;
    private float scale;

    public PinchZoom(Context context, TextView target, float initialScale, Listener listener) {
        this.target = target;
        this.listener = listener;
        this.baseSize = target.getTextSize();
        this.scale = clamp(initialScale);
        this.detector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector source) {
                        setScale(scale * source.getScaleFactor(), false);
                        return true;
                    }
                });
        target.setOnTouchListener(this);
        applyTextSize();
    }

    @Override
    public boolean onTouch(View view, MotionEvent event) {
        int action = event.getActionMasked();
        if (event.getPointerCount() > 1 || action == MotionEvent.ACTION_POINTER_DOWN) {
            disallowIntercept(view, true);
        }
        detector.onTouchEvent(event);
        boolean finished = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                || (action == MotionEvent.ACTION_POINTER_UP && event.getPointerCount() <= 2);
        if (finished) {
            disallowIntercept(view, false);
            if (listener != null) {
                listener.onScale(scale, true);
            }
        }
        // 不消耗事件：让 TextView/EditText 继续处理选择、点击与自身滚动。
        return false;
    }

    public float getScale() {
        return scale;
    }

    /** 设置缩放比例（会立即生效并回调）。 */
    public void setScale(float value, boolean finished) {
        float clamped = clamp(value);
        boolean changed = Math.abs(clamped - scale) > 0.0001f;
        scale = clamped;
        if (changed) {
            applyTextSize();
        }
        if (listener != null && changed) {
            listener.onScale(scale, finished);
        }
    }

    private void applyTextSize() {
        target.setTextSize(TypedValue.COMPLEX_UNIT_PX, baseSize * scale);
    }

    private void disallowIntercept(View view, boolean disallow) {
        android.view.ViewParent parent = view.getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(disallow);
        }
    }

    private static float clamp(float value) {
        if (Float.isNaN(value) || value <= 0f) {
            return DEFAULT_SCALE;
        }
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, value));
    }
}
