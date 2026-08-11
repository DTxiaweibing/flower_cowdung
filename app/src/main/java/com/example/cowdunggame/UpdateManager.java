package com.example.cowdunggame;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 应用升级检查。
 *
 * 约定：服务端提供一份 JSON（update.json），结构如下：
 * {
 *   "version": "1.0.1",            // 最新版本号
 *   "update_log": "更新内容描述",
 *   "apk_url": "https://.../app.apk"   // 可选的下载地址
 * }
 *
 * update.json 的读取地址（HTTP URL）目前【留空】，
 * 待部署方确定后填入 UPDATE_URL。
 */
public class UpdateManager {

    private static final String TAG = "UpdateManager";

    /** ★ 读取 update.json 的位置（留空，待部署后填写）★ */
    private static final String UPDATE_URL =
            "https://raw.githubusercontent.com/DTxiaweibing/flower_cowdung/master/update.json";

    private static final String PREFS_NAME = "update_mgr";
    private static final String KEY_UPDATE_SEEN = "update_version_seen";
    private static final String KEY_IGNORED_VERSION = "ignored_version";
    private static final String KEY_REMIND_LATER_UNTIL = "remind_later_until";
    private static final long REMIND_DELAY_MS = 24 * 60 * 60 * 1000L;

    private final Context context;
    private final SharedPreferences prefs;
    private final Handler mainHandler;
    private final ExecutorService executor;
    private UpdateCallback callback;

    public interface UpdateCallback {
        void onUpdateAvailable(String latestVersion, String updateLog, String apkUrl);
        void onUpdateChecked(boolean hasUpdate);
        void onError(String error);
    }

    public UpdateManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.executor = Executors.newSingleThreadExecutor();
    }

    public void setCallback(UpdateCallback callback) {
        this.callback = callback;
    }

    public boolean hasUnseenUpdate() {
        if (!prefs.getBoolean("has_update", false)) return false;
        long remindUntil = prefs.getLong(KEY_REMIND_LATER_UNTIL, 0);
        return System.currentTimeMillis() >= remindUntil;
    }

    public void remindLater() {
        long until = System.currentTimeMillis() + REMIND_DELAY_MS;
        prefs.edit().putLong(KEY_REMIND_LATER_UNTIL, until).apply();
    }

    public String getUpdateVersion() {
        return prefs.getString("latest_version", "");
    }

    public String getUpdateLog() {
        return prefs.getString("update_log", "");
    }

    public String getApkUrl() {
        return prefs.getString("apk_url", "");
    }

    public void markUpdateSeen() {
        prefs.edit()
            .putBoolean("has_update", false)
            .putString(KEY_UPDATE_SEEN, getUpdateVersion())
            .apply();
    }

    public void ignoreVersion(String version) {
        prefs.edit()
            .putString(KEY_IGNORED_VERSION, version)
            .putBoolean("has_update", false)
            .apply();
    }

    public boolean isVersionIgnored(String version) {
        return version != null && version.equals(prefs.getString(KEY_IGNORED_VERSION, ""));
    }

    public void checkForUpdate(final String currentVersion) {
        executor.execute(() -> {
            try {
                final JSONObject update = fetchUpdateJson();
                if (update == null) {
                    // 地址未配置或获取失败：不打扰用户，视为无更新
                    notifyChecked(false);
                    return;
                }

                // 样式：版本号与下载地址都在 file_info 节点下
                JSONObject fileInfo = update.getJSONObject("file_info");
                String latestVersion = fileInfo.getString("version");
                String updateLog = update.optString("update_log", context.getString(R.string.update_default_log));
                String apkUrl = fileInfo.optString("download_url", "");

                if (isNewerVersion(latestVersion, currentVersion)) {
                    if (!isVersionIgnored(latestVersion)) {
                        prefs.edit()
                            .putBoolean("has_update", true)
                            .putString("latest_version", latestVersion)
                            .putString("update_log", updateLog)
                            .putString("apk_url", apkUrl)
                            .apply();
                        notifyUpdateAvailable(latestVersion, updateLog, apkUrl);
                    } else {
                        prefs.edit().putBoolean("has_update", false).apply();
                        notifyChecked(false);
                    }
                } else {
                    prefs.edit().putBoolean("has_update", false).apply();
                    notifyChecked(false);
                }
            } catch (Exception e) {
                Log.e(TAG, "检查更新异常: " + e.getMessage());
                notifyError(e.getMessage());
            }
        });
    }

    /**
     * 从 UPDATE_URL 拉取并解析 update.json。
     * 因地址目前留空，直接返回 null；地址确定后实现为：
     *   HttpURLConnection GET UPDATE_URL → 解析 JSONObject 返回。
     */
    private JSONObject fetchUpdateJson() {
        if (UPDATE_URL == null || UPDATE_URL.trim().isEmpty()) {
            Log.w(TAG, "UPDATE_URL 尚未配置，跳过更新检查");
            return null;
        }
        HttpURLConnection conn = null;
        try {
            URL url = new URL(UPDATE_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)");

            if (conn.getResponseCode() == 200) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    return new JSONObject(sb.toString());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "获取更新信息失败: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    public static boolean isNewerVersion(String latest, String current) {
        if (latest == null || current == null) return false;
        if (latest.equals(current)) return false;
        try {
            String[] lp = latest.split("\\.");
            String[] cp = current.split("\\.");
            int max = Math.max(lp.length, cp.length);
            for (int i = 0; i < max; i++) {
                int l = i < lp.length ? Integer.parseInt(lp[i]) : 0;
                int c = i < cp.length ? Integer.parseInt(cp[i]) : 0;
                if (l > c) return true;
                if (l < c) return false;
            }
            return false;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void notifyUpdateAvailable(final String version, final String log, final String apkUrl) {
        mainHandler.post(() -> {
            if (callback != null) callback.onUpdateAvailable(version, log, apkUrl);
        });
    }

    private void notifyChecked(final boolean hasUpdate) {
        mainHandler.post(() -> {
            if (callback != null) callback.onUpdateChecked(hasUpdate);
        });
    }

    private void notifyError(final String error) {
        mainHandler.post(() -> {
            if (callback != null) callback.onError(error);
        });
    }

    public void shutdown() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }
}