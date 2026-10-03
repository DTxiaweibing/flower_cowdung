// FlowerTheme.java
// 鲜花主题：主页下拉抽屉里挑的花，棋盘与对局日志统一从这里取，保证两处永远一致。
// 只收录 Emoji 1.0（Unicode 6.0）的花，minSdk=28（Android 9，字体覆盖到 Emoji 11）
// 的设备必现，不会出现豆腐块。玫瑰排在第一并作为默认值。
package com.example.cowdunggame;

import android.content.Context;
import android.content.SharedPreferences;

public final class FlowerTheme {

    private static final String PREFS = "CowDungPrefs";
    private static final String KEY_INDEX = "flower_idx";

    // 名称与 emoji 一一对应，玫瑰在第一位（默认）。
    public static final String[] NAMES = {
        "玫瑰", "郁金香", "木槿", "向日葵", "花朵", "樱花", "花束", "白花", "枯萎的花"
    };
    public static final String[] EMOJIS = {
        "🌹", "🌷", "🌺", "🌻", "🌼", "🌸", "💐", "💮", "🥀"
    };

    private FlowerTheme() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static int getIndex(Context c) {
        int i = prefs(c).getInt(KEY_INDEX, 0);
        if (i < 0 || i >= EMOJIS.length) i = 0;
        return i;
    }

    public static void setIndex(Context c, int index) {
        if (index < 0 || index >= EMOJIS.length) index = 0;
        prefs(c).edit().putInt(KEY_INDEX, index).apply();
    }

    public static String getEmoji(Context c) {
        return EMOJIS[getIndex(c)];
    }

    public static String getName(Context c) {
        return NAMES[getIndex(c)];
    }

    // 重复当前花 count 次，用于日志「5朵🌹🌹🌹🌹🌹」
    public static String repeat(Context c, int count) {
        String e = getEmoji(c);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) sb.append(e);
        return sb.toString();
    }
}
