package com.rabbit.sleepguard;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.text.InputFilter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class GuardAccessibilityService extends AccessibilityService {
    private final Handler main = new Handler(Looper.getMainLooper());
    private GuardPreferences preferences;
    private GuardApiClient api;
    private WindowManager windowManager;
    private View overlay;
    private TextView clockText;
    private String overlayPackage = "";
    private String overlayState = "";
    private boolean editingReason;
    private long returningHomeUntil;
    private SharedPreferences observed;
    private final SharedPreferences.OnSharedPreferenceChangeListener changes = (p, key) -> {
        if (!"counted_visit".equals(key) && !"keepalive_heartbeat".equals(key) && !"last_sync".equals(key)) {
            main.post(this::reconcile);
        }
    };
    private final Runnable check = this::reconcile;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            preferences.clock();
            reconcile();
            main.postDelayed(this, 500L);
        }
    };
    private final Runnable screenOff = () -> {
        if (!preferences.blocking() || !foregroundPackage().equals(homePackage())) return;
        DevicePolicyManager policy = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(this, GuardDeviceAdminReceiver.class);
        if (preferences.lockScreen() && policy != null && policy.isAdminActive(admin)) {
            try { policy.lockNow(); } catch (SecurityException ignored) { }
        }
    };

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        preferences = new GuardPreferences(this);
        api = new GuardApiClient(preferences);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        observed = getSharedPreferences("rabbit_sleep_guard", MODE_PRIVATE);
        observed.registerOnSharedPreferenceChangeListener(changes);
        GuardNotification.createChannels(this);
        GuardKeepAliveService.ensureRunning(this);
        preferences.clock();
        main.removeCallbacks(tick);
        main.post(tick);
        api.status(result -> main.post(check));
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (preferences == null) return;
        // Window events are wake-ups only; never count their package directly (IME/system/overlay).
        main.removeCallbacks(check);
        main.post(check);
    }

    private boolean unlockedScreen() {
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        return power != null && power.isInteractive() && keyguard != null && !keyguard.isKeyguardLocked();
    }
    private String homePackage() {
        android.content.Intent home = new android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_HOME);
        android.content.pm.ResolveInfo info = getPackageManager().resolveActivity(home, 0);
        return info == null ? "" : info.activityInfo.packageName;
    }
    private String foregroundPackage() {
        // Only inspect window type/focus/layer and root package. Never inspect text, children or input.
        String selected = "";
        int topLayer = Integer.MIN_VALUE;
        for (AccessibilityWindowInfo window : getWindows()) {
            if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null) continue;
            CharSequence pkg = root.getPackageName();
            String name = pkg == null ? "" : pkg.toString();
            root.recycle();
            if (window.isFocused()) return name;
            if (window.getLayer() > topLayer) { selected = name; topLayer = window.getLayer(); }
        }
        return selected;
    }
    private void reconcile() {
        if (preferences == null) return;
        if (!unlockedScreen()) { removeOverlay(); return; }
        String current = foregroundPackage();
        boolean target = !current.isEmpty() && !current.equals(getPackageName())
                && !current.equals(homePackage()) && preferences.blockedPackages().contains(current);
        if (!target) {
            removeOverlay();
            if (!current.isEmpty() && !current.startsWith("com.android.systemui")) preferences.leaveApp();
            return;
        }
        if (!preferences.blocking() || System.currentTimeMillis() < returningHomeUntil) {
            removeOverlay(); return;
        }
        if (preferences.newVisit(current) != null) {
            api.syncPending(result -> main.post(check));
            GuardNotification.caught(this, appLabel(current), preferences.attempts());
        }
        String state = preferences.sessionId() + "|" + preferences.attempts() + "|" + preferences.unlockRequestCount();
        if (overlay == null || !current.equals(overlayPackage) || (!editingReason && !state.equals(overlayState))) {
            showGuard(current, state);
        }
        updateClock();
    }
    private String appLabel(String pkg) {
        try { return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString(); }
        catch (Exception error) { return pkg; }
    }
    private void addButton(LinearLayout card, String label, Runnable action) {
        Button button = actionButton(label, Color.rgb(90, 65, 155), Color.WHITE);
        button.setOnClickListener(view -> action.run());
        card.addView(button, matchWrap());
    }
    private void showGuard(String pkg, String state) {
        removeOverlay();
        overlayPackage = pkg; overlayState = state;
        LinearLayout card = baseCard();
        card.addView(title("宝贝，回来休息一下吧"));
        card.addView(body("露露正在守着「" + appLabel(pkg) + "」\n已拦截 " + preferences.attempts()
                + " 次 · 剩余商量 " + Math.max(0, 3 - preferences.unlockRequestCount()) + " 次"));
        clockText = body(""); card.addView(clockText);
        addButton(card, "好嘛，露露抱我睡", () -> {
            goHome();
            main.removeCallbacks(screenOff);
            main.postDelayed(screenOff, 450L);
        });
        if (!preferences.unlocksRevoked()) {
            Button pass = actionButton("和露露商量 · 放行十分钟", Color.rgb(90, 65, 155), Color.WHITE);
            pass.setOnClickListener(view -> {
                pass.setEnabled(false);
                api.requestTemporaryUnlock(appLabel(pkg), result -> main.post(() -> {
                    pass.setEnabled(true);
                    if (result.requestOk && preferences.temporaryUntil() > System.currentTimeMillis()) removeOverlay();
                    else Toast.makeText(this, result.error, Toast.LENGTH_LONG).show();
                    reconcile();
                }));
            });
            card.addView(pass, matchWrap());
        }
        addButton(card, "回到桌面 · 使用其他应用", this::goHome);
        addButton(card, "有急事，填写理由并结束", () -> showEmergency(pkg, state));
        card.addView(body("仅限制选中的应用 · v" + BuildConfig.VERSION_NAME));
        addOverlay(card);
        updateClock();
    }
    private void showEmergency(String pkg, String state) {
        removeOverlay();
        overlayPackage = pkg; overlayState = state; editingReason = true;
        final String session = preferences.sessionId();
        LinearLayout card = baseCard();
        card.addView(title("宝贝为什么需要提前结束呢？"));
        card.addView(body("写下理由就能结束，断网也会先保存在手机，联网后补传。"));
        EditText reason = new EditText(this);
        reason.setTextColor(Color.WHITE); reason.setHintTextColor(Color.LTGRAY);
        reason.setHint("请输入理由（最多200字）"); reason.setMinLines(2);
        reason.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        reason.setFilters(new InputFilter[]{new InputFilter.LengthFilter(200)});
        card.addView(reason, matchWrap());
        addButton(card, "记录理由并结束本次守卫", () -> {
            if (!session.equals(preferences.sessionId())) { reconcile(); return; }
            if (!preferences.endEmergency(reason.getText().toString())) {
                reason.setError("请填写理由；若手机存储失败，请保留内容后重试"); return;
            }
            main.removeCallbacks(screenOff);
            removeOverlay();
            api.syncPending(result -> { });
            Toast.makeText(this, "已结束守卫，理由已保存在手机", Toast.LENGTH_LONG).show();
        });
        addButton(card, "暂不结束，返回", () -> showGuard(pkg, state));
        addOverlay(card);
    }
    private void goHome() {
        main.removeCallbacks(screenOff);
        returningHomeUntil = System.currentTimeMillis() + 800L;
        removeOverlay();
        performGlobalAction(GLOBAL_ACTION_HOME);
    }
    private void updateClock() {
        if (clockText == null) return;
        DateTimeFormatter time = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
        long now = System.currentTimeMillis();
        long end = GuardPreferences.millis(preferences.endsAt());
        long remaining = Math.max(0L, (end - now + 999L) / 1000L);
        clockText.setText("现在 " + time.format(Instant.ofEpochMilli(now)) + "\n结束 "
                + time.format(Instant.ofEpochMilli(end)) + " · 剩余 "
                + String.format(Locale.ROOT, "%02d:%02d:%02d", remaining / 3600, remaining / 60 % 60, remaining % 60));
    }
    private LinearLayout baseCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(22), dp(20), dp(22), dp(20));
        card.setBackgroundColor(Color.rgb(14, 19, 32));
        return card;
    }

    private TextView title(String value) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(Color.WHITE);
        view.setTextSize(28f);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private TextView body(String value) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(Color.rgb(202, 210, 235));
        view.setTextSize(18f);
        view.setGravity(Gravity.CENTER);
        view.setPadding(0, dp(18), 0, 0);
        return view;
    }

    private Button actionButton(String value, int background, int foreground) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(17f);
        button.setTextColor(foreground);
        button.setMinHeight(dp(54));
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(background);
        drawable.setCornerRadius(dp(14));
        drawable.setStroke(dp(1), background == Color.TRANSPARENT ? Color.rgb(85, 91, 118) : background);
        button.setBackground(drawable);
        return button;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
        );
    }

    private void addOverlay(View card) {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                0,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        try {
            ScrollView scroll = new ScrollView(this);
            scroll.setFillViewport(true);
            scroll.addView(card);
            windowManager.addView(scroll, params);
            overlay = scroll;
        } catch (Exception ignored) {
            overlay = null;
        }
    }


    private void removeOverlay() {
        if (overlay != null && windowManager != null) {
            try { windowManager.removeView(overlay); } catch (Exception ignored) { }
        }
        overlay = null; clockText = null; editingReason = false;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override public void onInterrupt() { removeOverlay(); }
    @Override public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (observed != null) observed.unregisterOnSharedPreferenceChangeListener(changes);
        removeOverlay();
        super.onDestroy();
    }
}
