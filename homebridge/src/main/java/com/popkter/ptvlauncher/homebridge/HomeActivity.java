package com.popkter.ptvlauncher.homebridge;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Button;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;

/** A system HOME entry that waits for boot-time package availability without opening Settings. */
public final class HomeActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long started;
    private int attempts;
    private TextView status;
    private Button recovery;
    private final Runnable retry = this::launchPtv;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        started = SystemClock.elapsedRealtime();
        getWindow().getDecorView().setSystemUiVisibility(
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        // The visible surface is only needed when the firmware has not yet exposed PTV.
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{0xff162938, 0xff263d4b}));
        TextView title = new TextView(this);
        title.setText("PTV");title.setTextSize(42);title.setTextColor(Color.WHITE);
        layout.addView(title);
        status = new TextView(this);
        status.setText("正在启动桌面…");status.setTextSize(16);status.setTextColor(0xffccd6de);
        status.setPadding(0,24,0,24);layout.addView(status);
        recovery = new Button(this);
        recovery.setText("系统设置");recovery.setVisibility(android.view.View.GONE);
        recovery.setOnClickListener(view -> startActivity(new Intent(Settings.ACTION_SETTINGS)));
        layout.addView(recovery);
        setContentView(layout);
    }
    @Override protected void onResume() {
        super.onResume();handler.removeCallbacks(retry);launchPtv();
    }
    @Override protected void onPause() {
        handler.removeCallbacks(retry);super.onPause();
    }
    @Override protected void onDestroy() {
        handler.removeCallbacks(retry);super.onDestroy();
    }
    private void launchPtv() {
        if(isFinishing() || isDestroyed())return;
        try {
            startActivity(new Intent(Intent.ACTION_MAIN)
                .setComponent(new ComponentName("com.popkter.ptvlauncher",
                    "com.popkter.ptvlauncher.MainActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION));
            Log.i("PTVHomeBridge", "PTV started after " + (SystemClock.elapsedRealtime()-started) + "ms, retries=" + attempts);
            finish();
        } catch (ActivityNotFoundException | SecurityException error) {
            if(attempts++ == 0)Log.w("PTVHomeBridge", "Waiting for PTV package availability", error);
            if(SystemClock.elapsedRealtime()-started > 30_000) {
                status.setText("桌面尚未就绪，正在重试…");
                recovery.setVisibility(android.view.View.VISIBLE);
            }
            handler.postDelayed(retry, attempts < 100 ? 200 : 1000);
        }
    }
}
