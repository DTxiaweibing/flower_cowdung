// SupabaseClient.java
// 极简纯 Java Supabase REST 客户端（HttpURLConnection，无第三方依赖）
//   匿名登录 Auth + 通用 RPC 调用 + 会话/token 本地持久化。
//   认证头同时携带 apikey + Authorization(用户 JWT)，兼容新版 sb_publishable_* key 与 RLS。
package com.example.cowdunggame;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class SupabaseClient {

    public static final String PROJECT_URL =
        "https://uihalfuswgilzzhzmgpv.supabase.co";
    public static final String ANON_KEY =
        "sb_publishable_HaCpd4tIhhaunf8S-b7FoQ_6uBGzbLM";

    private static final String PREFS = "CowDungPrefs";
    private static final String KEY_TOKEN   = "SupabaseToken";
    private static final String KEY_USER   = "SupabaseUserId";
    private static final String KEY_EXP    = "SupabaseTokenExp";
    private static final String KEY_REFRESH = "SupabaseRefreshToken";

    private final Context context;
    private String accessToken;
    private String refreshToken;
    private long accessTokenExp;
    private String userId;

    public SupabaseClient(Context context) {
        this.context = context.getApplicationContext();
        loadSession();
    }

    // ---- 会话持久化 ----
    private void loadSession() {
        SharedPreferences sp = prefs();
        accessToken = sp.getString(KEY_TOKEN, null);
        refreshToken = sp.getString(KEY_REFRESH, null);
        accessTokenExp = sp.getLong(KEY_EXP, 0);
        userId = sp.getString(KEY_USER, null);
    }

    private void saveSession() {
        prefs().edit()
            .putString(KEY_TOKEN, accessToken)
            .putString(KEY_REFRESH, refreshToken)
            .putLong(KEY_EXP, accessTokenExp)
            .putString(KEY_USER, userId)
            .apply();
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean hasSession() {
        return accessToken != null && userId != null;
    }

    public String getUserId() {
        return userId;
    }

    public String getAccessToken() {
        return accessToken;
    }

    // ---- 匿名登录：POST /auth/v1/signup  body {} ----
    public static class AuthResult {
        public boolean ok;
        public String error;
        public JSONObject session; // 含 access_token / refresh_token / user
    }

    public AuthResult signInAnonymously() {
        AuthResult r = new AuthResult();
        try {
            JSONObject resp = postJson(
                PROJECT_URL + "/auth/v1/signup",
                new JSONObject(),
                null);
            if (resp == null || resp.has("error")) {
                r.ok = false;
                r.error = resp == null ? "no_response" : resp.optString("error_description",
                    resp.optString("msg", resp.optString("error", "unknown")));
                return r;
            }
            r.ok = true;
            r.session = resp;
            captureSession(resp);
        } catch (Exception e) {
            r.ok = false;
            r.error = String.valueOf(e.getMessage());
        }
        return r;
    }

    // 刷新 token：POST /auth/v1/token?grant_type=refresh_token
    public AuthResult refreshSession() {
        AuthResult r = new AuthResult();
        if (refreshToken == null) {
            r.ok = false;
            r.error = "no_refresh_token";
            return r;
        }
        try {
            JSONObject body = new JSONObject();
            body.put("refresh_token", refreshToken);
            JSONObject resp = postJson(
                PROJECT_URL + "/auth/v1/token?grant_type=refresh_token",
                body,
                null);
            if (resp == null || resp.has("error")) {
                r.ok = false;
                r.error = resp == null ? "no_response" : "refresh_failed";
                return r;
            }
            r.ok = true;
            r.session = resp;
            captureSession(resp);
        } catch (Exception e) {
            r.ok = false;
            r.error = String.valueOf(e.getMessage());
        }
        return r;
    }

    private void captureSession(JSONObject s) throws Exception {
        accessToken = s.optString("access_token", null);
        refreshToken = s.optString("refresh_token", refreshToken);
        long exp = s.optLong("expires_in", 3600);
        accessTokenExp = System.currentTimeMillis() / 1000 + exp;
        JSONObject user = s.optJSONObject("user");
        if (user != null) userId = user.optString("id", null);
        saveSession();
    }

    // 若 access token 过期/即将过期则刷新
    public boolean ensureFreshToken() {
        if (accessToken == null) return false;
        long now = System.currentTimeMillis() / 1000;
        if (accessTokenExp - now < 30) {
            AuthResult r = refreshSession();
            return r.ok;
        }
        return true;
    }

    // ---- 通用 RPC：POST /rest/v1/rpc/{name} ----
    public static class RpcResult {
        public boolean ok;
        public String error;
        public JSONObject json;    // 若返回对象
        public JSONArray array;    // 若返回数组
        public String rawText;
    }

    public RpcResult rpc(String name, JSONObject args) {
        RpcResult r = new RpcResult();
        try {
            ensureFreshToken();
            if (accessToken == null) {
                r.ok = false;
                r.error = "not_authenticated";
                return r;
            }
            HttpURLConnection conn = open(PROJECT_URL + "/rest/v1/rpc/" + name);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("apikey", ANON_KEY);
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setRequestProperty("Prefer", "return=representation");
            conn.setDoOutput(true);
            byte[] body = (args == null ? "{}" : args.toString())
                .getBytes(StandardCharsets.UTF_8);
            OutputStream os = conn.getOutputStream();
            os.write(body);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            String text = read(conn);
            r.rawText = text;
            if (code >= 200 && code < 300) {
                r.ok = true;
                try {
                    if (text.startsWith("[")) {
                        r.array = new JSONArray(text);
                    } else {
                        r.json = new JSONObject(text);
                    }
                } catch (Exception ignore) { /* 空结果 */ }
            } else {
                r.ok = false;
                r.error = extractError(text, code);
            }
            conn.disconnect();
        } catch (Exception e) {
            r.ok = false;
            r.error = String.valueOf(e.getMessage());
        }
        return r;
    }

    // 通用 GET JSON，返回解析后的 JSONArray
    public JSONArray getJsonArray(String pathAndQuery) {
        try {
            if (!ensureFreshToken() || accessToken == null) return null;
            URL url = new URL(PROJECT_URL + pathAndQuery);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("apikey", ANON_KEY);
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            int code = conn.getResponseCode();
            String text = read(conn);
            conn.disconnect();
            if (code >= 200 && code < 300 && text.trim().startsWith("[")) {
                return new JSONArray(text);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    // 查询大厅全部未完结桌子（含玩家/观众字段）
    public JSONArray fetchLobbyTables() {
        return getJsonArray("/rest/v1/lobby_tables?select=*&order=created_at.asc");
    }

    // 查询全部用户资料（渲染头像性别映射 id -> gender）
    public JSONArray fetchProfiles() {
        return getJsonArray("/rest/v1/profiles?select=id,nickname,gender&order=created_at.asc");
    }

    // 查询所有私人房间（供单桌游戏厅）
    public JSONArray fetchRooms() {
        return getJsonArray("/rest/v1/rooms?select=*&order=created_at.asc");
    }

    // 建桌：create_lobby_table RPC，返回桌号 text
    public String createLobbyTable() {
        SupabaseClient.RpcResult rr = rpc("create_lobby_table", new JSONObject());
        if (!rr.ok) return null;
        if (rr.json != null && rr.json.has("_rpc_result")) return rr.json.optString("_rpc_result");
        // 标量返回：原始文本可能是 "ABC123" 带引号
        if (rr.rawText != null) {
            String t = rr.rawText.trim();
            if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
                return t.substring(1, t.length() - 1);
            }
            return t;
        }
        return null;
    }

    // 建私房：create_room RPC，返回 4 位房号
    public String createRoom() {
        RpcResult rr = rpc("create_room", new JSONObject());
        if (!rr.ok) return null;
        if (rr.rawText != null) {
            String t = rr.rawText.trim();
            if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
                return t.substring(1, t.length() - 1);
            }
            return t;
        }
        return null;
    }

    // 入座大厅桌
    public boolean joinTable(String tableId) {
        try {
            JSONObject args = new JSONObject();
            args.put("tid", tableId);
            return rpc("join_table", args).ok;
        } catch (Exception e) {
            return false;
        }
    }

    // 加入私房
    public boolean joinRoom(String code) {
        try {
            JSONObject args = new JSONObject();
            args.put("code", code);
            return rpc("join_room", args).ok;
        } catch (Exception e) {
            return false;
        }
    }

    // 心跳：更新本人 last_seen_at（离线判定依据）
    public void heartbeat() {
        if (userId == null || accessToken == null) return;
        try {
            URL url = new URL(PROJECT_URL + "/rest/v1/profiles?id=eq." + userId);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("PATCH");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("apikey", ANON_KEY);
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setDoOutput(true);
            OutputStream os = conn.getOutputStream();
            os.write("{\"last_seen_at\":\"now\"}".getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();
            conn.getResponseCode();
            conn.disconnect();
        } catch (Exception ignore) { }
    }

    // 读取本机个人资料行（PostgREST GET /rest/v1/profiles?id=eq.{user}），未建档返回 null
    public JSONObject readOwnProfile() {
        try {
            if (!ensureFreshToken() || accessToken == null || userId == null) return null;
            String urlStr = PROJECT_URL + "/rest/v1/profiles?id=eq." + userId;
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("apikey", ANON_KEY);
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            int code = conn.getResponseCode();
            String text = read(conn);
            conn.disconnect();
            if (code >= 200 && code < 300 && text.trim().startsWith("[")) {
                JSONArray arr = new JSONArray(text);
                return arr.length() > 0 ? arr.getJSONObject(0) : null;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    // 通用 POST JSON，返回解析后的 JSONObject
    private JSONObject postJson(String urlStr, JSONObject body, String token)
        throws Exception {
        HttpURLConnection conn = open(urlStr);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("apikey", ANON_KEY);
        if (token != null) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        } else if (accessToken != null) {
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
        }
        conn.setDoOutput(true);
        OutputStream os = conn.getOutputStream();
        os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        os.flush();
        os.close();

        int code = conn.getResponseCode();
        String text = read(conn);
        conn.disconnect();
        if (code < 200 || code >= 300) {
            JSONObject err = new JSONObject();
            err.put("error", code);
            err.put("error_description", text);
            return err;
        }
        try {
            return text.trim().isEmpty() ? new JSONObject() : new JSONObject(text);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private HttpURLConnection open(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        return conn;
    }

    private String read(HttpURLConnection conn) throws Exception {
        InputStream is = conn.getErrorStream();
        if (is == null) is = conn.getInputStream();
        if (is == null) return "";
        BufferedReader br = new BufferedReader(
            new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }

    private String extractError(String text, int code) {
        if (text == null || text.trim().isEmpty()) return "http_" + code;
        try {
            JSONArray arr = new JSONArray(text);
            if (arr.length() > 0) {
                JSONObject o = arr.optJSONObject(0);
                if (o != null) {
                    String m = o.optString("message", null);
                    if (m != null) return m;
                }
            }
            return text;
        } catch (Exception e) {
            // 部分 RPC 报错返回纯文本格式：{"code":"XXX","message":"..."} 或 "XXX"
            String t = text.trim();
            if (t.startsWith("\"") && t.endsWith("\"")) {
                return t.substring(1, t.length() - 1);
            }
            return t;
        }
    }
}
