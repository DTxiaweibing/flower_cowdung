// BgmManager.java
// 全应用背景音乐：单一 MediaPlayer 循环播放，由"前台状态 + 音乐开关"共同控制。
// 初始化在 GlobalApplication，前后台切换由 ActivityLifecycleCallbacks 驱动，
// 因此界面间跳转不会重启播放，实现无缝循环。
package com.example.cowdunggame;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;

public class BgmManager {

    private static final String PREFS = "CowDungPrefs";
    private static final String KEY_MUSIC = "musicEnabled";

    private static BgmManager sInstance;
    private MediaPlayer player;
    private Context appContext;
    private boolean foreground = false;
    private boolean prepared = false;

    public static BgmManager get() {
        if (sInstance == null) sInstance = new BgmManager();
        return sInstance;
    }

    public void init(Context context) {
        if (player != null) return;
        appContext = context.getApplicationContext();
        try {
            player = MediaPlayer.create(appContext, R.raw.bgm);
            if (player == null) return;
            player.setLooping(true);
            player.setVolume(0.4f, 0.4f); // 背景音乐音量低于音效，避免盖过短音效
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                player.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build());
            }
            prepared = true;
            apply();
        } catch (Exception e) {
            player = null;
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
        if (player == null || !prepared) return;
        boolean shouldPlay = foreground && isMusicEnabled();
        try {
            if (shouldPlay && !player.isPlaying()) {
                player.start();
            } else if (!shouldPlay && player.isPlaying()) {
                player.pause();
            }
        } catch (Exception e) {
            // 忽略瞬态错误
        }
    }

    public void release() {
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) {}
            player.release();
            player = null;
        }
        prepared = false;
    }
}
