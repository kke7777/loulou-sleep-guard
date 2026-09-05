package com.rabbit.sleepguard;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.time.Instant;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class GuardPreferencesTest {
    private Context context;
    private GuardPreferences prefs;
    private SharedPreferences disk;
    private JSONObject snapshot(String id, long revision, long start) throws Exception {
        return new JSONObject().put("protocol_version", 2).put("revision", revision)
                .put("session_id", id).put("started_at", Instant.ofEpochMilli(start).toString())
                .put("active", true).put("attempts", 0).put("unlock_request_count", 0)
                .put("ends_at", Instant.ofEpochMilli(System.currentTimeMillis() + 3_600_000).toString());
    }
    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        disk = context.getSharedPreferences("rabbit_sleep_guard", Context.MODE_PRIVATE);
        disk.edit().clear().commit();
        prefs = new GuardPreferences(context);
        prefs.saveConnection("https://sleep.example.com", "a".repeat(32));
    }
    @Test public void offlineReasonSurvivesRestartAndOldServerSnapshots() throws Exception {
        JSONObject old = snapshot("session-1", 1, System.currentTimeMillis() - 1000);
        prefs.applySnapshot(old);
        assertFalse(prefs.endEmergency("  "));
        assertTrue(prefs.endEmergency("单位有急事"));
        prefs = new GuardPreferences(context);
        assertFalse(prefs.cachedActive());
        assertEquals("单位有急事", prefs.pendingEvent().getString("reason"));
        old.put("revision", 2);
        prefs.applySnapshot(old);
        assertFalse(prefs.cachedActive());
        assertTrue(prefs.emergencyHistory().contains("单位有急事"));
        prefs.acknowledge(prefs.pendingEvent().getString("request_id"));
        assertNull(prefs.pendingEvent());
        prefs.applySnapshot(old.put("revision", 3));
        assertFalse(prefs.cachedActive());
    }
    @Test public void newerExplicitSessionWorksButPreviouslyUnknownOldSessionCannotRelock() throws Exception {
        long before = System.currentTimeMillis() - 10_000;
        prefs.applySnapshot(snapshot("session-1", 1, before));
        assertTrue(prefs.endEmergency("付款"));
        prefs.applySnapshot(snapshot("unknown-old-session", 2, before));
        assertFalse(prefs.cachedActive());
        prefs.applySnapshot(snapshot("new-session", 3, System.currentTimeMillis() + 1));
        assertTrue(prefs.cachedActive());
        assertEquals(0, prefs.unlockRequestCount());
    }
    @Test public void repeatedWindowAndScreenEventsDoNotCountAgain() throws Exception {
        prefs.applySnapshot(snapshot("session-1", 1, System.currentTimeMillis() - 1000));
        assertNotNull(prefs.newVisit("browser"));
        assertNull(prefs.newVisit("browser"));
        prefs = new GuardPreferences(context);
        assertNull(prefs.newVisit("browser"));
        assertEquals(1, prefs.attempts());
        prefs.leaveApp();
        assertNotNull(prefs.newVisit("browser"));
        assertEquals(2, prefs.attempts());
    }
    @Test public void thirdPassRemainsUsableAndGuardExpiryWins() throws Exception {
        JSONObject pass = snapshot("session-1", 1, System.currentTimeMillis() - 1000)
                .put("unlock_request_count", 3).put("unlocks_revoked", true)
                .put("temporary_unlock_until", Instant.ofEpochMilli(System.currentTimeMillis() + 600_000).toString());
        prefs.applySnapshot(pass);
        assertTrue(prefs.cachedActive());
        assertFalse(prefs.blocking());
        assertTrue(prefs.unlocksRevoked());
        prefs.applySnapshot(pass.put("revision", 2).put("ends_at", "2000-01-01T00:00:00Z"));
        assertFalse(prefs.cachedActive());
        assertFalse(prefs.blocking());
    }
    @Test public void lateResponsesCannotResetPassQuotaOrState() throws Exception {
        long start = System.currentTimeMillis() - 1000;
        prefs.applySnapshot(snapshot("session-1", 5, start).put("unlock_request_count", 2));
        prefs.applySnapshot(snapshot("session-1", 4, start));
        assertEquals(2, prefs.unlockRequestCount());
    }
    @Test public void scheduleWorksOfflineAt0020AcrossMultipleDays() {
        long before = Instant.parse("2026-09-05T16:19:59Z").toEpochMilli();
        prefs.clock(before);
        assertFalse(disk.getBoolean("active", false));
        prefs.clock(before + 1000);
        assertEquals("night-2026-09-05T22:30:00.000Z", prefs.sessionId());
        assertTrue(disk.getBoolean("active", false));
        prefs.clock(Instant.parse("2026-09-05T22:30:00Z").toEpochMilli());
        assertFalse(disk.getBoolean("active", false));
        prefs.clock(Instant.parse("2026-09-06T16:20:00Z").toEpochMilli());
        assertEquals("night-2026-09-06T22:30:00.000Z", prefs.sessionId());
        assertTrue(disk.getBoolean("active", false));
    }
    @Test public void stalePreScheduleResponseCannotCancelLocalAutoStart() throws Exception {
        prefs.clock(Instant.parse("2026-09-05T16:20:00Z").toEpochMilli());
        prefs.applySnapshot(new JSONObject().put("protocol_version", 2).put("revision", 1).put("active", false));
        assertTrue(disk.getBoolean("active", false));
    }
}
