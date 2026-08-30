package com.rabbit.sleepguard;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class GuardKeepAliveService extends Service {
    private static final long POLL_INTERVAL_MS = 60_000L;
    private static final long HEARTBEAT_INTERVAL_MS = 15_000L;
    private static final long STREAM_RETRY_MS = 3_000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService streamExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean streamStarted = new AtomicBoolean(false);

    private GuardPreferences preferences;
    private GuardApiClient api;
    private volatile boolean stopping = false;
    private volatile HttpURLConnection streamConnection;

    public static void ensureRunning(Context context) {
        Intent intent = new Intent(context, GuardKeepAliveService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception ignored) {
        }
    }

    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            if (preferences != null) {
                preferences.markKeepAliveHeartbeat();
                GuardNotification.updateKeepAlive(
                        GuardKeepAliveService.this,
                        preferences.cachedActive(),
                        preferences.attempts()
                );
            }
            main.postDelayed(this, HEARTBEAT_INTERVAL_MS);
        }
    };

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (api != null && preferences.configured()) {
                api.status(result -> main.post(() -> {
                    if (result.requestOk) {
                        preferences.markKeepAliveHeartbeat();
                        GuardNotification.updateKeepAlive(
                                GuardKeepAliveService.this,
                                result.active,
                                result.attempts
                        );
                    }
                }));
            }
            main.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        preferences = new GuardPreferences(this);
        api = new GuardApiClient(preferences);
        GuardNotification.createChannels(this);
        preferences.markKeepAliveHeartbeat();
        startForeground(
                GuardNotification.KEEPALIVE_ID,
                GuardNotification.keepAliveNotification(this, preferences.cachedActive(), preferences.attempts())
        );
        main.post(heartbeat);
        main.post(poll);
        startEventStream();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        preferences.markKeepAliveHeartbeat();
        startEventStream();
        return START_STICKY;
    }

    private void startEventStream() {
        if (!preferences.configured() || !streamStarted.compareAndSet(false, true)) return;
        streamExecutor.execute(() -> {
            while (!stopping) {
                runOneStream();
                if (stopping) break;
                try {
                    Thread.sleep(STREAM_RETRY_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            streamStarted.set(false);
        });
    }

    private void runOneStream() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(preferences.serverUrl() + "/api/device/stream").openConnection();
            streamConnection = connection;
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(0);
            connection.setRequestProperty("Authorization", "Bearer " + preferences.deviceToken());
            connection.setRequestProperty("Accept", "text/event-stream");
            connection.setRequestProperty("Cache-Control", "no-cache");

            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) return;
            preferences.markKeepAliveHeartbeat();

            InputStream input = connection.getInputStream();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while (!stopping && (line = reader.readLine()) != null) {
                    preferences.markKeepAliveHeartbeat();
                    if (!line.startsWith("data:")) continue;
                    applyStreamState(line.substring(5).trim());
                }
            }
        } catch (Exception ignored) {
        } finally {
            streamConnection = null;
            if (connection != null) connection.disconnect();
        }
    }

    private void applyStreamState(String jsonText) {
        try {
            JSONObject json = new JSONObject(jsonText);
            boolean active = json.optBoolean("active", false);
            int attempts = json.optInt("attempts", 0);
            int unlockRequestCount = json.optInt("unlock_request_count", 0);
            boolean unlocksRevoked = json.optBoolean("unlocks_revoked", false);
            String endsAt = json.optString("ends_at", "");
            preferences.updateState(active, attempts, unlockRequestCount, unlocksRevoked, endsAt);
            preferences.markKeepAliveHeartbeat();
            main.post(() -> GuardNotification.updateKeepAlive(
                    GuardKeepAliveService.this,
                    active,
                    attempts
            ));
        } catch (Exception ignored) {
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        preferences.markKeepAliveHeartbeat();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        stopping = true;
        main.removeCallbacksAndMessages(null);
        HttpURLConnection connection = streamConnection;
        if (connection != null) connection.disconnect();
        streamExecutor.shutdownNow();
        super.onDestroy();
    }
}
