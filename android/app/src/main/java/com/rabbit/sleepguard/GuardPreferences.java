package com.rabbit.sleepguard;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;
import org.json.JSONArray;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

public final class GuardPreferences {
    private static final String FILE = "rabbit_sleep_guard";
    private static final String SERVER_URL = "server_url";
    private static final String DEVICE_TOKEN = "device_token";
    private static final String BLOCKED_PACKAGES = "blocked_packages";
    private static final String LOCK_SCREEN = "lock_screen";
    private static final String ACTIVE = "active";
    private static final String ATTEMPTS = "attempts";
    private static final String UNLOCK_REQUEST_COUNT = "unlock_request_count";
    private static final String UNLOCKS_REVOKED = "unlocks_revoked";
    private static final String ENDS_AT = "ends_at";
    private static final String LAST_SYNC = "last_sync";
    private static final String KEEPALIVE_HEARTBEAT = "keepalive_heartbeat";
    private static final String GUARD_PAGE_VISIBLE = "guard_page_visible";
    private static final String GUARDED_APP_NAME = "guarded_app_name";

    private final SharedPreferences preferences;

    public GuardPreferences(Context context) {
        preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public String serverUrl() {
        return preferences.getString(SERVER_URL, "").replaceAll("/+$", "");
    }

    public String deviceToken() {
        return preferences.getString(DEVICE_TOKEN, "");
    }

    public void saveConnection(String serverUrl, String deviceToken) {
        preferences.edit()
                .putString(SERVER_URL, serverUrl.trim().replaceAll("/+$", ""))
                .putString(DEVICE_TOKEN, deviceToken.trim())
                .apply();
    }

    public Set<String> blockedPackages() {
        return Collections.unmodifiableSet(new HashSet<>(
                preferences.getStringSet(BLOCKED_PACKAGES, Collections.emptySet())
        ));
    }

    public void saveBlockedPackages(Set<String> packages) {
        preferences.edit().putStringSet(BLOCKED_PACKAGES, new HashSet<>(packages)).apply();
    }

    public boolean lockScreen() {
        return preferences.getBoolean(LOCK_SCREEN, true);
    }

    public void setLockScreen(boolean enabled) {
        preferences.edit().putBoolean(LOCK_SCREEN, enabled).apply();
    }

    public boolean cachedActive() {
        if (!preferences.getBoolean(ACTIVE, false)) return false;
        String end = preferences.getString(ENDS_AT, "");
        if (end == null || end.isEmpty()) return true;
        try {
            return java.time.Instant.parse(end).toEpochMilli() > System.currentTimeMillis();
        } catch (Exception ignored) {
            return true;
        }
    }

    public int attempts() {
        return preferences.getInt(ATTEMPTS, 0);
    }

    public int unlockRequestCount() {
        return preferences.getInt(UNLOCK_REQUEST_COUNT, 0);
    }

    public boolean unlocksRevoked() {
        return preferences.getBoolean(UNLOCKS_REVOKED, false);
    }

    public String endsAt() {
        return preferences.getString(ENDS_AT, "");
    }

    public long lastSync() {
        return preferences.getLong(LAST_SYNC, 0L);
    }

    public void markKeepAliveHeartbeat() {
        preferences.edit().putLong(KEEPALIVE_HEARTBEAT, System.currentTimeMillis()).apply();
    }

    public long keepAliveHeartbeat() {
        return preferences.getLong(KEEPALIVE_HEARTBEAT, 0L);
    }

    public boolean keepAliveRecent() {
        long heartbeat = keepAliveHeartbeat();
        return heartbeat > 0L && System.currentTimeMillis() - heartbeat < 45_000L;
    }

    public boolean guardPageVisible() {
        return preferences.getBoolean(GUARD_PAGE_VISIBLE, false);
    }

    public String guardedAppName() {
        return preferences.getString(GUARDED_APP_NAME, "受限应用");
    }

    public void showGuardPage(String appName) {
        preferences.edit()
                .putBoolean(GUARD_PAGE_VISIBLE, true)
                .putString(GUARDED_APP_NAME, appName == null || appName.isEmpty() ? "受限应用" : appName)
                .apply();
    }

    public void dismissGuardPage() {
        preferences.edit()
                .putBoolean(GUARD_PAGE_VISIBLE, false)
                .remove(GUARDED_APP_NAME)
                .apply();
    }

    private static final Object STATE_LOCK = new Object();

    public static long millis(String value) {
        try { return Instant.parse(value).toEpochMilli(); } catch (Exception ignored) { return 0L; }
    }
    public String sessionId() { return preferences.getString("session_id", ""); }
    public String startedAt() { return preferences.getString("started_at", ""); }
    public long temporaryUntil() { return preferences.getLong("temporary_until", 0L); }
    public boolean blocking() { return cachedActive() && temporaryUntil() <= System.currentTimeMillis(); }
    public int startMinute() { return preferences.getInt("schedule_start", 20); }
    public int wakeMinute() { return preferences.getInt("schedule_wake", 390); }
    public ZoneOffset offset() { return ZoneOffset.ofTotalSeconds(preferences.getInt("schedule_offset", 480) * 60); }
    public long nextWake(long now) {
        ZonedDateTime local = Instant.ofEpochMilli(now).atZone(offset());
        ZonedDateTime wake = local.toLocalDate().atStartOfDay(offset()).plusMinutes(wakeMinute());
        if (wake.toInstant().toEpochMilli() <= now) wake = wake.plusDays(1);
        return wake.toInstant().toEpochMilli();
    }
    public long nextStart(long now) {
        ZonedDateTime local = Instant.ofEpochMilli(now).atZone(offset());
        ZonedDateTime start = local.toLocalDate().atStartOfDay(offset()).plusMinutes(startMinute());
        if (start.toInstant().toEpochMilli() <= now) start = start.plusDays(1);
        return start.toInstant().toEpochMilli();
    }
    public boolean inNight(long now) {
        ZonedDateTime local = Instant.ofEpochMilli(now).atZone(offset());
        int minute = local.getHour() * 60 + local.getMinute();
        return startMinute() < wakeMinute() ? minute >= startMinute() && minute < wakeMinute()
                : minute >= startMinute() || minute < wakeMinute();
    }
    private String nightId(long now) {
        // Match Javascript Date.toISOString(), including milliseconds.
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                .withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(nextWake(now)));
    }
    public void clock() { clock(System.currentTimeMillis()); }
    void clock(long now) {
        synchronized (STATE_LOCK) {
            if (!configured()) return;
            String night = nightId(now);
            if (inNight(now) && preferences.getBoolean(ACTIVE, false)
                    && millis(endsAt()) > nextStart(now) - 86_400_000L) {
                preferences.edit().putString("local_night", night).apply();
            }
            if (preferences.getBoolean(ACTIVE, false) && millis(endsAt()) <= now) {
                preferences.edit().putBoolean(ACTIVE, false).putLong("temporary_until", 0L).apply();
            }
            if (!preferences.getBoolean(ACTIVE, false) && inNight(now)
                    && preferences.getLong("local_suppressed", 0L) <= now
                    && !night.equals(preferences.getString("local_night", ""))) {
                preferences.edit().putBoolean(ACTIVE, true).putString("session_id", "night-" + night)
                        .putString("started_at", Instant.ofEpochMilli(now).toString()).putString(ENDS_AT, night)
                        .putInt(ATTEMPTS, 0).putInt(UNLOCK_REQUEST_COUNT, 0).putBoolean(UNLOCKS_REVOKED, false)
                        .putLong("temporary_until", 0L).putString("local_night", night)
                        .putBoolean("local_auto", true).commit();
            }
        }
    }
    private JSONArray pendingUnsafe() {
        try { return new JSONArray(preferences.getString("event_outbox", "[]")); }
        catch (Exception error) { throw new IllegalStateException("无法读取本地待同步记录", error); }
    }
    public JSONObject pendingEvent() {
        synchronized (STATE_LOCK) { return pendingUnsafe().optJSONObject(0); }
    }
    public void acknowledge(String id) {
        synchronized (STATE_LOCK) {
            JSONArray old = pendingUnsafe(), next = new JSONArray();
            for (int i = 0; i < old.length(); i++) {
                JSONObject row = old.optJSONObject(i);
                if (row != null && !id.equals(row.optString("request_id"))) next.put(row);
            }
            preferences.edit().putString("event_outbox", next.toString()).commit();
        }
    }
    public boolean endEmergency(String rawReason) {
        synchronized (STATE_LOCK) {
            String reason = rawReason.trim();
            if (reason.isEmpty() || reason.codePointCount(0, reason.length()) > 200 || sessionId().isEmpty()) return false;
            try {
                JSONObject row = new JSONObject();
                row.put("event", "emergency_guard_ended").put("request_id", java.util.UUID.randomUUID().toString())
                        .put("source", "android_emergency").put("session_id", sessionId()).put("reason", reason)
                        .put("occurred_at", Instant.now().toString()).put("original_ends_at", endsAt())
                        .put("original_started_at", startedAt());
                JSONArray queue = pendingUnsafe(); queue.put(row);
                JSONArray history = new JSONArray(preferences.getString("emergency_history", "[]")); history.put(row);
                Set<String> ended = new HashSet<>(preferences.getStringSet("ended_sessions", Collections.emptySet()));
                ended.add(sessionId());
                // One synchronous disk commit stores reason, outbox and local override before releasing UI.
                java.util.Map<String, ?> before = preferences.getAll();
                boolean saved = preferences.edit().putString("event_outbox", queue.toString())
                        .putString("emergency_history", history.toString()).putStringSet("ended_sessions", ended)
                        .putLong("local_suppressed", nextWake(System.currentTimeMillis()))
                        .putLong("local_end_at", System.currentTimeMillis())
                        .putBoolean(ACTIVE, false).putLong("temporary_until", 0L).commit();
                if (!saved) restoreMemory(before);
                return saved;
            } catch (Exception error) { return false; }
        }
    }
    @SuppressWarnings("unchecked")
    private void restoreMemory(java.util.Map<String, ?> before) {
        SharedPreferences.Editor edit = preferences.edit().clear();
        for (java.util.Map.Entry<String, ?> item : before.entrySet()) {
            Object value = item.getValue(); String key = item.getKey();
            if (value instanceof String) edit.putString(key, (String) value);
            else if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) edit.putInt(key, (Integer) value);
            else if (value instanceof Long) edit.putLong(key, (Long) value);
            else if (value instanceof Float) edit.putFloat(key, (Float) value);
            else if (value instanceof Set) edit.putStringSet(key, (Set<String>) value);
        }
        edit.commit();
    }
    public String emergencyHistory() {
        synchronized (STATE_LOCK) {
            try {
                JSONArray history = new JSONArray(preferences.getString("emergency_history", "[]"));
                StringBuilder text = new StringBuilder();
                for (int i = history.length() - 1; i >= 0; i--) {
                    JSONObject row = history.getJSONObject(i);
                    text.append(row.optString("occurred_at")).append("\n").append(row.optString("reason")).append("\n\n");
                }
                return text.length() == 0 ? "还没有紧急结束记录" : text.toString();
            } catch (Exception error) { return "暂时无法读取记录"; }
        }
    }
    public JSONObject newVisit(String packageName) {
        synchronized (STATE_LOCK) {
            String key = sessionId() + "|" + packageName + "|" + temporaryUntil();
            if (key.equals(preferences.getString("counted_visit", ""))) return null;
            try {
                JSONObject row = new JSONObject().put("event", "blocked_app_opened")
                        .put("session_id", sessionId()).put("package_name", packageName).put("app_name", packageName)
                        .put("source", "android_accessibility").put("request_id", java.util.UUID.randomUUID().toString());
                JSONArray queue = pendingUnsafe(); queue.put(row);
                if (!preferences.edit().putString("counted_visit", key).putInt(ATTEMPTS, attempts() + 1)
                        .putString("event_outbox", queue.toString()).commit()) return null;
                return row;
            } catch (Exception error) { return null; }
        }
    }
    public void leaveApp() { preferences.edit().remove("counted_visit").apply(); }

    public void applySnapshot(JSONObject json) {
        synchronized (STATE_LOCK) {
            long revision = json.optLong("revision", 0L);
            if (revision < preferences.getLong("server_revision", -1L)) return;
            JSONObject rule = json.optJSONObject("schedule");
            SharedPreferences.Editor edit = preferences.edit().putLong(LAST_SYNC, System.currentTimeMillis());
            if (rule != null) {
                edit.putInt("schedule_start", rule.optInt("start_hour", 0) * 60 + rule.optInt("start_minute", 20))
                        .putInt("schedule_wake", rule.optInt("wake_hour", 6) * 60 + rule.optInt("wake_minute", 30))
                        .putInt("schedule_offset", rule.optInt("utc_offset_minutes", 480));
            }
            edit.apply();
            // Incompatible old server must not undo local safety decisions or claim ten-minute passes.
            if (json.optInt("protocol_version", 0) < 2) return;
            String session = json.optString("session_id", "");
            boolean ended = preferences.getStringSet("ended_sessions", Collections.emptySet()).contains(session);
            boolean same = session.equals(sessionId());
            boolean predatesEmergency = millis(json.optString("started_at")) <= preferences.getLong("local_end_at", -1L);
            // A locally scheduled session survives a delayed pre-schedule server response.
            if (preferences.getBoolean("local_auto", false) && !same && !json.optBoolean("active")
                    && millis(json.optString("auto_start_suppressed_until")) <= System.currentTimeMillis()) return;
            long suppressed = millis(json.optString("auto_start_suppressed_until"));
            boolean nightSuppressed = session.startsWith("night-")
                    && preferences.getLong("local_suppressed", 0L) > System.currentTimeMillis();
            int pendingVisits = 0;
            JSONArray queue = pendingUnsafe();
            for (int i = 0; i < queue.length(); i++) {
                JSONObject row = queue.optJSONObject(i);
                if (row != null && session.equals(row.optString("session_id")) && "blocked_app_opened".equals(row.optString("event"))) pendingVisits++;
            }
            edit = preferences.edit().putLong("server_revision", revision)
                    .putString("session_id", session).putString("started_at", json.optString("started_at", ""))
                    .putBoolean(ACTIVE, json.optBoolean("active") && !ended && !nightSuppressed && !predatesEmergency)
                    .putString(ENDS_AT, json.optString("ends_at", ""))
                    .putInt(ATTEMPTS, same && pendingVisits > 0 ? Math.max(attempts(), json.optInt("attempts")) : json.optInt("attempts"))
                    .putInt(UNLOCK_REQUEST_COUNT, json.optInt("unlock_request_count"))
                    .putBoolean(UNLOCKS_REVOKED, json.optBoolean("unlocks_revoked"))
                    .putLong("temporary_until", ended ? 0L : millis(json.optString("temporary_unlock_until")))
                    .putBoolean("local_auto", false);
            if (suppressed > 0) edit.putLong("local_suppressed", Math.max(suppressed, preferences.getLong("local_suppressed", 0L)));
            String night = json.optString("last_auto_night", "");
            if (!night.isEmpty() && !night.equals("null")) edit.putString("local_night", night);
            edit.commit();
        }
    }

    public boolean configured() {
        return serverUrl().startsWith("https://") && deviceToken().length() >= 24;
    }
}
