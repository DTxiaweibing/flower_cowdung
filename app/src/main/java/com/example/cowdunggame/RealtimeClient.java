// RealtimeClient.java
// Supabase Realtime 订阅（OkHttp WebSocket + Phoenix channel 协议）
//   订阅 lobby_tables / rooms / profiles 的 Postgres Changes：
//   任何 INSERT/UPDATE/DELETE 都推送，客户端据此实时刷新大厅/房间/在线状态。
package com.example.cowdunggame;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class RealtimeClient {

    public interface Listener {
        // table / room / profile 变更回调；record 为变更后的新行（org.json）
        void onChanged(String table, String event, JSONObject record);
    }

    private static final String WS_URL =
        "wss://uihalfuswgilzzhzmgpv.supabase.co/realtime/v1/websocket?apikey="
            + SupabaseClient.ANON_KEY + "&vsn=1.0.0";

    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebSocket webSocket;
    private OkHttpClient httpClient;
    private boolean subscribed;
    private int refCounter = 0;
    private final Runnable reconnectRunnable = new Runnable() {
        @Override
        public void run() {
            connect();
        }
    };

    public RealtimeClient(Listener listener) {
        this.listener = listener;
        OkHttpClient.Builder b = new OkHttpClient.Builder()
            .pingInterval(25, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS);
        httpClient = b.build();
    }

    public synchronized void subscribe() {
        if (webSocket != null) return;
        connect();
    }

    private void connect() {
        Request req = new Request.Builder().url(WS_URL).build();
        webSocket = httpClient.newWebSocket(req, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                subscribed = false;
                refCounter = 0;
                // 先加入 Phoenix 控制频道
                send(joinChannel("phoenix", null));
                // 订阅各表 Postgres Changes（schema=public, event=*）
                send(joinPostgresChanges("lobby_tables"));
                send(joinPostgresChanges("rooms"));
                send(joinPostgresChanges("profiles"));
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                handleMessage(text);
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                ws.cancel();
                webSocket = null;
                main.postDelayed(reconnectRunnable, 5000); // 断线重连
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                webSocket = null;
            }
        });
    }

    private String joinPostgresChanges(String table) {
        JsonObject body = new JsonObject();
        JsonObject bodyInner = new JsonObject();
        bodyInner.addProperty("event", "*");
        bodyInner.addProperty("schema", "public");
        bodyInner.addProperty("table", table);
        body.add("body", bodyInner);
        return send(joinChannel("realtime:postgres_changes", body));
    }

    private String joinChannel(String topic, JsonObject payload) {
        JsonObject m = new JsonObject();
        m.addProperty("topic", topic);
        m.addProperty("event", "phx_join");
        m.addProperty("ref", String.valueOf(++refCounter));
        if (payload != null) {
            m.add("payload", payload);
        } else {
            m.add("payload", new JsonObject());
        }
        return m.toString();
    }

    private synchronized String send(String json) {
        if (webSocket != null) {
            webSocket.send(json);
        }
        return json;
    }

    private void handleMessage(String text) {
        try {
            Gson gson = new Gson();
            JsonObject msg = gson.fromJson(text, JsonObject.class);
            if (msg == null) return;
            String topic = msg.has("topic") ? msg.get("topic").getAsString() : "";
            String event = msg.has("event") ? msg.get("event").getAsString() : "";
            JsonObject payload = msg.has("payload") ? msg.getAsJsonObject("payload") : null;

            // 心跳回执：Phoenix 要求回复 heartbeat
            if ("phoenix".equals(topic) && "heartbeat".equals(event)) {
                JsonObject reply = new JsonObject();
                reply.addProperty("topic", "phoenix");
                reply.addProperty("event", "heartbeat");
                reply.addProperty("ref", msg.get("ref").getAsString());
                reply.add("payload", new JsonObject());
                send(reply.toString());
                return;
            }

            // 订阅确认后开始跟踪变化
            if ("realtime:postgres_changes".equals(topic)
                && "phx_reply".equals(event)
                && payload != null && "ok".equals(payload.get("status").getAsString())) {
                subscribed = true;
                return;
            }

            // 真正的变更数据：event = postgres_changes, payload.data.table
            if (payload != null && payload.has("data")) {
                JsonObject data = payload.getAsJsonObject("data");
                String table = data.has("table") ? data.get("table").getAsString() : "";
                String ev = data.has("type") ? data.get("type").getAsString() : "";
                JsonObject record = data.has("record") ? data.getAsJsonObject("record") : null;
                if (record != null) {
                    final String ftable = table;
                    final String fev = ev;
                    final JSONObject frec;
                    try {
                        frec = new JSONObject(gson.toJson(record));
                    } catch (Exception e) {
                        return;
                    }
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (listener != null) listener.onChanged(ftable, fev, frec);
                        }
                    });
                }
            }
        } catch (Exception ignore) { }
    }

    public void close() {
        if (webSocket != null) {
            webSocket.close(1000, "bye");
            webSocket = null;
        }
        main.removeCallbacks(reconnectRunnable);
    }
}