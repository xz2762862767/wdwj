package com.mtstyle.fm;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import java.util.concurrent.atomic.AtomicBoolean;

/** 在后台线程执行耗时文件操作，并显示带进度条的模态对话框。 */
public final class TaskRunner {

    private TaskRunner() {
    }

    public interface Job {
        void run(FileOps.Progress progress) throws Exception;
    }

    public interface Callback {
        void onFinished(boolean ok, String message);
    }

    public static void run(final Activity activity, String title, boolean cancellable,
                           final Job job, final Callback callback) {
        final Handler handler = new Handler(Looper.getMainLooper());
        final AtomicBoolean cancelled = new AtomicBoolean(false);

        final TextView messageView = new TextView(activity);
        messageView.setText("准备中…");
        messageView.setTextSize(13f);

        final ProgressBar bar = new ProgressBar(activity, null,
                android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(0);

        int pad = (int) (18 * activity.getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, pad / 2);
        box.addView(messageView);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barParams.topMargin = pad / 2;
        box.addView(bar, barParams);

        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(box)
                .setCancelable(false)
                .setNegativeButton(cancellable ? "取消" : null,
                        (d, w) -> cancelled.set(true))
                .create();
        dialog.show();

        final FileOps.Progress progress = new FileOps.Progress() {
            @Override
            public void publish(final String message, final int percent) {
                handler.post(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) {
                        return;
                    }
                    messageView.setText(message);
                    if (percent >= 0) {
                        bar.setProgress(Math.min(100, percent));
                    }
                });
            }

            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }
        };

        new Thread(() -> {
            boolean ok = true;
            String result = "操作完成";
            try {
                job.run(progress);
            } catch (FileOps.Cancelled c) {
                ok = false;
                result = "操作已取消";
            } catch (Exception e) {
                ok = false;
                result = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final boolean doneOk = ok;
            final String doneMsg = result;
            handler.post(() -> {
                try {
                    dialog.dismiss();
                } catch (Exception ignored) {
                }
                if (callback != null && !activity.isFinishing()) {
                    callback.onFinished(doneOk, doneMsg);
                }
            });
        }, "fm-task").start();
    }
}
