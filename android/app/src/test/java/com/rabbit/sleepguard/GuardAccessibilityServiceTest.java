package com.rabbit.sleepguard;

import android.content.Context;
import android.os.PowerManager;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class GuardAccessibilityServiceTest {
    private GuardAccessibilityService service;
    private GuardPreferences prefs;
    private PowerManager power;
    private JSONObject state;
    @Before public void setup() throws Exception {
        service = Robolectric.buildService(GuardAccessibilityService.class).create().get();
        service.getSharedPreferences("rabbit_sleep_guard", Context.MODE_PRIVATE).edit().clear().commit();
        prefs = new GuardPreferences(service);
        prefs.saveBlockedPackages(Set.of("test.browser"));
        ReflectionHelpers.setField(service, "preferences", prefs);
        ReflectionHelpers.setField(service, "api", new GuardApiClient(prefs));
        ReflectionHelpers.setField(service, "windowManager", service.getSystemService(Context.WINDOW_SERVICE));
        power = (PowerManager) service.getSystemService(Context.POWER_SERVICE);
        Shadows.shadowOf(power).setIsInteractive(true);
        state = new JSONObject().put("protocol_version", 2).put("revision", 1).put("active", true)
                .put("session_id", "test-session").put("started_at", Instant.now().toString())
                .put("ends_at", Instant.ofEpochMilli(System.currentTimeMillis() + 3_600_000).toString());
        prefs.applySnapshot(state);
    }
    @After public void cleanup() { service.onDestroy(); }
    private void window(String pkg) {
        AccessibilityNodeInfo root = AccessibilityNodeInfo.obtain(); root.setPackageName(pkg);
        AccessibilityWindowInfo window = AccessibilityWindowInfo.obtain();
        Shadows.shadowOf(window).setRoot(root);
        Shadows.shadowOf(window).setType(AccessibilityWindowInfo.TYPE_APPLICATION);
        Shadows.shadowOf(window).setFocused(true);
        Shadows.shadowOf(service).setWindows(List.of(window));
    }
    private void check() { ReflectionHelpers.callInstanceMethod(service, "reconcile"); }
    private Object overlay() { return ReflectionHelpers.getField(service, "overlay"); }
    @Test public void remoteActivationCatchesCurrentBrowserWithoutNewWindowEvent() throws Exception {
        prefs.applySnapshot(state.put("revision", 2).put("active", false));
        window("test.browser"); check(); assertNull(overlay());
        prefs.applySnapshot(state.put("revision", 3).put("active", true));
        check(); assertNotNull(overlay()); assertEquals(1, prefs.attempts());
        check(); assertEquals(1, prefs.attempts());
    }
    @Test public void unrestrictedAppsRemainAvailableAfterQuotaExhaustionAndScreenCycle() throws Exception {
        prefs.applySnapshot(state.put("revision", 2).put("unlock_request_count", 3).put("unlocks_revoked", true));
        window("test.browser"); check(); assertNotNull(overlay());
        window("test.unrestricted"); check(); assertNull(overlay());
        Shadows.shadowOf(power).setIsInteractive(false); check();
        Shadows.shadowOf(power).setIsInteractive(true); check();
        assertNull(overlay()); assertEquals(1, prefs.attempts());
    }
    @Test public void sameBrowserScreenCycleDoesNotAddVisit() {
        window("test.browser"); check();
        Shadows.shadowOf(power).setIsInteractive(false); check(); assertNull(overlay());
        Shadows.shadowOf(power).setIsInteractive(true); check();
        assertNotNull(overlay()); assertEquals(1, prefs.attempts());
    }
    @Test public void systemPanelDoesNotTrapUserOrCreateAnotherVisit() {
        window("test.browser"); check(); assertNotNull(overlay());
        AccessibilityWindowInfo panel = AccessibilityWindowInfo.obtain();
        Shadows.shadowOf(panel).setType(AccessibilityWindowInfo.TYPE_SYSTEM);
        Shadows.shadowOf(panel).setFocused(true);
        Shadows.shadowOf(service).setWindows(List.of(panel));
        check(); assertNull(overlay());
        window("test.browser"); check();
        assertNotNull(overlay()); assertEquals(1, prefs.attempts());
    }
    @Test public void expiryRemovesExistingOverlayWithoutClick() throws Exception {
        window("test.browser"); check(); assertNotNull(overlay());
        prefs.applySnapshot(state.put("revision", 2).put("ends_at", "2000-01-01T00:00:00Z"));
        check(); assertNull(overlay());
    }
}
