// BgmManager.java
// 全应用背景音乐：双 MediaPlayer 交叉淡变循环，根治 MP3 循环接缝"咔哒"爆音。
// - 单一播放器 cur 正常播放；临近结尾（FADE_MS）前启动第二播放器 nxt 从头播放，
//   两路在 FADE_MS 内做音量交叉淡变，淡变结束释放旧播放器、交换指针，从而实现无缝循环。
// - 交叉淡变在代码里完成，因此不依赖具体音频格式是否完美对齐。
// - 初始化在 GlobalApplication，前后台/音乐开关由 ActivityLifecycleCallbacks + 偏好驱动，
//   界面间跳转只 pause/resume，不会重启播放。
package com.example.cowdunggame;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

public class BgmManager {

    private static final String PREFS = "CowDungPrefs";
    private static final String KEY_MUSIC = "musicEnabled";

    private static final int FADE_MS = 100;          // 与音频重制时的交叉淡变时长一致
    private static final float VOL = 0.4f;           // 背景音乐音量低于音效

    private static BgmManager sInstance;
    private MediaPlayer cur;
    private MediaPlayer nxt;
    private Context appContext;
    private boolean foreground = false;
    private boolean playing = false;
    private boolean prepared = false;

    private final Handler h = new Handler(Looper.getMainLooper());
    private Runnable scheduleTask;
    private Runnable rampTask;

    public static BgmManager get() {
        if (sInstance == null) sInstance = new BgmManager();
        return sInstance;
    }

    public void init(Context context) {
        if (prepared) return;
        appContext = context.getApplicationContext();
        cur = createPlayer();
        prepared = (cur != null);
        apply();
    }

    private MediaPlayer createPlayer() {
        try {
            MediaPlayer p = MediaPlayer.create(appContext, R.raw.bgm);
            if (p == null) return null;
            p.setLooping(false); // 循环由代码交叉淡变接管
            p.setVolume(VOL, VOL);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                p.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build());
            }
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isMusicEnabled() {
        if (appContext == null) return true;
        return appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_MUSIC, true);
    }

    public void setMusicEnabled(Context context, boolean on) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_MUSIC, on).apply();
        apply();
    }

    public void setForeground(boolean fg) {
        foreground = fg;
        apply();
    }

    private void apply() {
        boolean shouldPlay = foreground && isMusicEnabled();
        if (shouldPlay && !playing) {
            startPlayback();
        } else if (!shouldPlay && playing) {
            stopPlayback();
        }
    }

    private void startPlayback() {
        if (cur == null) cur = createPlayer();
        if (cur == null) return;
        playing = true;
        try {
            cur.setVolume(VOL, VOL);
            if (!cur.isPlaying()) cur.start();
        } catch (Exception ignored) {}
        scheduleCrossfade();
    }

    private void stopPlayback() {
        playing = false;
        cancelTasks();
        try { if (cur != null && cur.isPlaying()) cur.pause(); } catch (Exception ignored) {}
        if (nxt != null) {
            try { nxt.release(); } catch (Exception ignored) {}
            nxt = null;
        }
    }

    private void cancelTasks() {
        if (scheduleTask != null) h.removeCallbacks(scheduleTask);
        if (rampTask != null) h.removeCallbacks(rampTask);
    }

    // 周期性检查 cur 是否临近结尾；临近则启动交叉淡变
    private void scheduleCrossfade() {
        cancelTasks();
        scheduleTask = new Runnable() {
            @Override
            public void run() {
                if (!playing || cur == null) return;
                try {
                    int pos = cur.getCurrentPosition();
                    int dur = cur.getDuration();
                    if (dur > 0 && (dur - pos) <= FADE_MS + 40) {
                        beginCrossfade();
                        return;
                    }
                } catch (Exception ignored) {}
                h.postDelayed(this, 40);
            }
        };
        h.postDelayed(scheduleTask, 40);
    }

    // 启动 nxt 从头播放，与 cur 尾段做 FADE_MS 交叉淡变，结束后释放旧播放器并交换
    private void beginCrossfade() {
        if (nxt == null) nxt = createPlayer();
        if (nxt == null) {
            // 无法创建第二路，退化为简单循环
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (cur != null && !cur.isPlaying()) {
                        try { cur.seekTo(0); cur.start(); } catch (Exception ignored) {}
                    }
                    scheduleCrossfade();
                }
            }, Math.max(0, (cur != null ? cur.getDuration() - cur.getCurrentPosition() : 0)));
            return;
        }
        try {
            nxt.setVolume(0f, 0f);
            nxt.seekTo(0);
            nxt.start();
        } catch (Exception ignored) {
            return;
        }
        final long t0 = System.currentTimeMillis();
        cancelRamp();
        rampTask = new Runnable() {
            @Override
            public void run() {
                if (!playing) return;
                float t = (System.currentTimeMillis() - t0) / (float) FADE_MS;
                if (t >= 1f) {
                    if (nxt != null) nxt.setVolume(VOL, VOL);
                    if (cur != null) {
                        try { cur.setVolume(0f, 0f); cur.pause(); cur.release(); } catch (Exception ignored) {}
                    }
                    cur = nxt;
                    nxt = null;
                    scheduleCrossfade();
                } else {
                    float v = t * VOL;
                    float u = (1f - t) * VOL;
                    if (nxt != null) nxt.setVolume(v, v);
                    if (cur != null) cur.setVolume(u, u);
                    h.postDelayed(this, 10);
                }
            }
        };
        h.postDelayed(rampTask, 10);
    }

    private void cancelRamp() {
        if (rampTask != null) h.removeCallbacks(rampTask);
    }

    public void release() {
        playing = false;
        cancelTasks();
        if (cur != null) {
            try { cur.release(); } catch (Exception ignored) {}
            cur = null;
        }
        if (nxt != null) {
            try { nxt.release(); } catch (Exception ignored) {}
            nxt = null;
        }
        prepared = false;
    }
}
