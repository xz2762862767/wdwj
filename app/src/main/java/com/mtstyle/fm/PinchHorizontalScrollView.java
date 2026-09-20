package com.mtstyle.fm;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.HorizontalScrollView;

/** 多指手势交给子视图（双指缩放），单指横向滚动保持原样。 */
public class PinchHorizontalScrollView extends HorizontalScrollView {

    public PinchHorizontalScrollView(Context context) {
        super(context);
    }

    public PinchHorizontalScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public PinchHorizontalScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (event.getPointerCount() > 1) {
            return false;
        }
        return super.onInterceptTouchEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getPointerCount() > 1) {
            return true;
        }
        return super.onTouchEvent(event);
    }
}
