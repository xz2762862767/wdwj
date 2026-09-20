package com.mtstyle.fm;

import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * MT 风格下拉刷新容器。
 *
 * 不使用 Material 的大圆圈指示器，而是从路径栏下方露出一条浅色细条：
 * 下拉时显示「小箭头 + 下拉刷新」，越过阈值变为「小箭头(翻转) + 松开立即刷新」，
 * 刷新过程中只保留文字与一条 2dp 的不定长细进度线。
 */
public class MtPullRefresh extends FrameLayout {

    /** 用于判断内容是否已经滑到顶部（滑到顶部才允许下拉刷新）。 */
    public interface ScrollChecker {
        boolean canScrollUp();
    }

    public interface OnRefreshListener {
        void onRefresh();
    }

    private final int maxPull;
    private final int trigger;
    private final int holdOffset;
    private final int touchSlop;

    private View indicator;
    private View content;
    private ImageView arrow;
    private TextView text;
    private View line;

    private ScrollChecker scrollChecker;
    private OnRefreshListener listener;

    private float startY;
    private float pull;
    private boolean dragging;
    private boolean refreshing;

    public MtPullRefresh(Context context) {
        this(context, null);
    }

    public MtPullRefresh(Context context, AttributeSet attrs) {
        super(context, attrs);
        float d = getResources().getDisplayMetrics().density;
        maxPull = (int) (64 * d);
        trigger = (int) (44 * d);
        holdOffset = (int) (30 * d);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setClipChildren(true);
    }

    public void setScrollChecker(ScrollChecker checker) {
        this.scrollChecker = checker;
    }

    public void setOnRefreshListener(OnRefreshListener l) {
        this.listener = l;
    }

    public boolean isRefreshing() {
        return refreshing;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        indicator = findViewById(R.id.pull_indicator);
        content = findViewById(R.id.pane_content);
        if (indicator != null) {
            arrow = indicator.findViewById(R.id.pull_arrow);
            text = indicator.findViewById(R.id.pull_text);
            line = indicator.findViewById(R.id.pull_line);
        }
        reset(0f);
    }

    private boolean canPull() {
        return scrollChecker == null || scrollChecker.canScrollUp() == false;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (refreshing || content == null || indicator == null) {
            return false;
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startY = ev.getRawY();
                dragging = false;
                return false;
            case MotionEvent.ACTION_MOVE:
                if (!canPull()) {
                    return false;
                }
                return ev.getRawY() - startY > touchSlop;
            default:
                return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (content == null || indicator == null) {
            return super.onTouchEvent(ev);
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (!dragging) {
                    dragging = true;
                }
                float d = (ev.getRawY() - startY) * 0.5f;
                if (d < 0) {
                    d = 0;
                }
                if (d > maxPull) {
                    d = maxPull;
                }
                reset(d);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean wasDragging = dragging;
                dragging = false;
                if (!wasDragging) {
                    return true;
                }
                if (pull >= trigger) {
                    startRefresh();
                } else {
                    animateTo(0f);
                }
                return true;
            }
            default:
                return true;
        }
    }

    /** 按当前下拉位移更新提示条外观。 */
    private void reset(float d) {
        pull = d;
        if (content != null) {
            content.setTranslationY(d);
        }
        if (indicator != null) {
            indicator.setTranslationY(d - maxPull);
        }
        if (arrow != null) {
            arrow.setRotation(d >= trigger ? 180f : 0f);
        }
        if (text != null) {
            text.setText(d >= trigger ? R.string.pull_release : R.string.pull_down);
        }
        if (line != null) {
            line.setVisibility(GONE);
        }
        if (arrow != null) {
            arrow.setVisibility(VISIBLE);
        }
    }

    private void startRefresh() {
        refreshing = true;
        if (arrow != null) {
            arrow.setVisibility(GONE);
        }
        if (text != null) {
            text.setText(R.string.refreshing);
        }
        if (line != null) {
            line.setVisibility(VISIBLE);
        }
        if (content != null) {
            content.setTranslationY(holdOffset);
        }
        if (indicator != null) {
            indicator.setTranslationY(holdOffset - maxPull);
        }
        if (listener != null) {
            listener.onRefresh();
        }
        postDelayed(this::finishRefresh, 460L);
    }

    private void finishRefresh() {
        refreshing = false;
        if (line != null) {
            line.setVisibility(GONE);
        }
        if (arrow != null) {
            arrow.setVisibility(VISIBLE);
        }
        animateTo(0f);
    }

    private void animateTo(final float target) {
        final float from = pull;
        ValueAnimator animator = ValueAnimator.ofFloat(from, target);
        animator.setDuration(180L);
        animator.addUpdateListener(a -> {
            float v = (Float) a.getAnimatedValue();
            if (content != null) {
                content.setTranslationY(v);
            }
            if (indicator != null) {
                indicator.setTranslationY(v - maxPull);
            }
        });
        animator.start();
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (target == 0f) {
                    reset(0f);
                }
            }
        });
    }
}
