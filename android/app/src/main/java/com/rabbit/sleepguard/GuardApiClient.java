package com.rabbit.sleepguard;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class GuardApiClient {
    public interface Callback {
        void complete(Result result);
    }

    public static final class Result {
        public final boolean requestOk;
        public final boolean active;
        public final boolean ignored;
        public final int attempts;
        public final int unlockRequestCount;
        public final boolean unlocksRevoked;
        public final String stage;
        public final String endsAt;
        public final String error;

        Result(boolean requestOk, boolean active, boolean ignored, int attempts,
               int unlockRequestCount, boolean unlocksRevoked, String stage,
               String endsAt, String error) {
            this.requestOk = requestOk;
            this.active = active;
            this.ignored = ignored;
            this.attempts = attempts;
            this.unlockRequestCount = unlockRequestCount;
            this.unlocksRevoked = unlocksRevoked;
            this.stage = stage;
            this.endsAt = endsAt;
            this.error = error;
        }
    }

    private static final ExecutorService NETWORK = Executors.newSingleThreadExecutor();
    private final GuardPreferences preferences;

    public GuardApiClient(GuardPreferences preferences) {
        this.preferences = preferences;
    }

    public void status(Callback callback) {
        request("GET", "/api/device/status", null, callback);
    }

    public void start(Callback callback) {
        event("sleep_guard_started", null, callback);
    }

    public void stop(Callback callback) {
        event("sleep_guard_ended", null, callback);
    }

    public void blocked(String appName, String packageName, Callback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("event", "blocked_app_opened");
            body.put("app_name", appName);
            body.put("package_name", packageName);
            body.put("source", "android_accessibility");
            body.put("request_id", java.util.UUID.randomUUID().toString());
        } catch (Exception ignored) {
        }
        request("POST", "/api/device/event", body, callback);
    }

    public void requestTemporaryUnlock(String appName, Callback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("event", "temporary_unlock_requested");
            body.put("app_name", appName);
            body.put("source", "android_accessibility");
            body.put("request_id", java.util.UUID.randomUUID().toString());
        } catch (Exception ignored) {
        }
        request("POST", "/api/device/event", body, callback);
    }

    private void event(String event, String endsAt, Callback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("event", event);
            body.put("source", "android_app");
            body.put("request_id", java.util.UUID.randomUUID().toString());
            if (endsAt != null) body.put("ends_at", endsAt);
        } catch (Exception ignored) {
        }
        request("POST", "/api/device/event", body, callback);
    }

    private void request(String method, String path, JSONObject body, Callback callback) {
        NETWORK.execute(() -> {
            Result result;
            if (!preferences.configured()) {
                result = new Result(false, false, false, 0, 0, false, "inactive", "", "请先填写 HTTPS 服务器地址和设备令牌");
            } else {
                result = execute(method, path, body);
            }
            callback.complete(result);
        });
    }

    private Result execute(String method, String path, JSONObject body) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(preferences.serverUrl() + path).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(2_500);
            connection.setReadTimeout(4_000);
            connection.setRequestProperty("Authorization", "Bearer " + preferences.deviceToken());
            connection.setRequestProperty("Accept", "application/json");
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }
            }
            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 400 ? connection.getInputStream() : connection.getErrorStream();
            StringBuilder text = new StringBuilder();
            if (stream != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) text.append(line);
                }
            }
            JSONObject json = text.length() == 0 ? new JSONObject() : new JSONObject(text.toString());
            boolean ok = code >= 200 && code < 300 && json.optBoolean("ok", true);
            boolean active = json.optBoolean("active", false);
            int attempts = json.optInt("attempts", 0);
            int unlockRequestCount = json.optInt("unlock_request_count", 0);
            boolean unlocksRevoked = json.optBoolean("unlocks_revoked", false);
            String stage = json.optString("stage", active ? "armed" : "inactive");
            String endsAt = json.optString("ends_at", "");
            boolean ignored = json.optBoolean("ignored", false);
            if (ok) preferences.updateState(active, attempts, unlockRequestCount, unlocksRevoked, endsAt);
            return new Result(ok, active, ignored, attempts, unlockRequestCount, unlocksRevoked,
                    stage, endsAt, ok ? "" : json.optString("error", "HTTP " + code));
        } catch (Exception error) {
            int attempts = preferences.attempts();
            return new Result(false, preferences.cachedActive(), false, attempts,
                    preferences.unlockRequestCount(), preferences.unlocksRevoked(), stageFor(attempts),
                    preferences.endsAt(), error.getClass().getSimpleName());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String stageFor(int attempts) {
        if (attempts <= 0) return "armed";
        if (attempts == 1) return "first_warning";
        if (attempts == 2) return "locked";
        return "refused_sleep";
    }
}
