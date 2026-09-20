package com.mtstyle.fm;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.ScrollView;

/** 多指手势交给子视图（双指缩放），单指滚动保持原样。 */
public class PinchScrollView extends ScrollView {

    public PinchScrollView(Context context) {
        super(context);
    }

    public PinchScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public PinchScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
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
