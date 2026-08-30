package com.rabbit.sleepguard;

import android.accessibilityservice.AccessibilityService;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Set;

public final class GuardAccessibilityService extends AccessibilityService {
    private static final long POLL_INTERVAL_MS = 300_000L;
    private static final long EVENT_DEBOUNCE_MS = 2_500L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private GuardPreferences preferences;
    private GuardApiClient api;
    private WindowManager windowManager;
    private View overlay;
    private String lastPackage = "";
    private long lastHandledAt = 0L;
    private boolean screenLockRequestedByUser = false;
    private boolean screenReceiverRegistered = false;

    private final Runnable lockAfterReturningHome = this::lockScreenIfRequested;

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                main.post(GuardAccessibilityService.this::restoreGuardPage);
            }
        }
    };

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (api != null && preferences.configured()) {
                api.status(result -> main.post(() -> {
                    if (result.requestOk && result.active) {
                        GuardNotification.showActive(GuardAccessibilityService.this, result.attempts);
                        restoreGuardPage();
                    } else if (result.requestOk) {
                        GuardNotification.hideActive(GuardAccessibilityService.this);
                        dismissGuardPage();
                    }
                }));
            }
            main.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        preferences = new GuardPreferences(this);
        api = new GuardApiClient(preferences);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        GuardNotification.createChannels(this);
        registerScreenReceiver();
        restoreGuardPage();
        main.removeCallbacks(poll);
        main.post(poll);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null || preferences == null) return;
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return;

        String packageName = event.getPackageName().toString();
        if (packageName.equals(getPackageName()) || packageName.startsWith("com.android.systemui")) return;
        Set<String> blocked = preferences.blockedPackages();
        if (!blocked.contains(packageName)) return;
        if (overlay != null) return;

        long now = System.currentTimeMillis();
        if (packageName.equals(lastPackage) && now - lastHandledAt < EVENT_DEBOUNCE_MS) return;
        lastPackage = packageName;
        lastHandledAt = now;

        String appName = appLabel(packageName);
        if (preferences.cachedActive()) showPendingOverlay(appName);
        api.blocked(appName, packageName, result -> main.post(() -> {
            if (result.requestOk && result.active && !result.ignored) {
                showGuardOverlay(appName, result.attempts, result.stage, result.unlocksRevoked);
                GuardNotification.showActive(this, result.attempts);
                GuardNotification.caught(this, appName, result.attempts);
            } else if (result.requestOk) {
                dismissGuardPage();
            } else if (!result.requestOk && preferences.cachedActive()) {
                int attempts = Math.max(1, preferences.attempts() + 1);
                showGuardOverlay(appName, attempts, stageFor(attempts), preferences.unlocksRevoked());
                GuardNotification.showActive(this, attempts);
            }
        }));
    }

    private String appLabel(String packageName) {
        try {
            PackageManager manager = getPackageManager();
            ApplicationInfo info = manager.getApplicationInfo(packageName, 0);
            return manager.getApplicationLabel(info).toString();
        } catch (Exception ignored) {
            return packageName;
        }
    }

    private void goBackToSleep() {
        main.removeCallbacks(lockAfterReturningHome);
        DevicePolicyManager policy = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(this, GuardDeviceAdminReceiver.class);
        boolean canLock = preferences.lockScreen() && policy != null && policy.isAdminActive(admin);
        if (canLock) {
            // Keep the accessibility overlay attached while the display is off. It will still be
            // the first interactive page when the user presses the power button again.
            screenLockRequestedByUser = true;
            performGlobalAction(GLOBAL_ACTION_HOME);
            main.postDelayed(lockAfterReturningHome, 450L);
        } else {
            screenLockRequestedByUser = false;
            dismissGuardPage();
            performGlobalAction(GLOBAL_ACTION_HOME);
        }
    }

    private void requestTemporaryUnlock(String appName, Button button) {
        button.setEnabled(false);
        button.setText("露露正在听宝贝说…");
        api.requestTemporaryUnlock(appName, result -> main.post(() -> {
            if (!result.requestOk) {
                button.setEnabled(true);
                button.setText("刚才没有听清……再说一次好吗");
                return;
            }
            if (!returnHomeAndDismissGuard()) {
                showGuardOverlay(appName, result.attempts, result.stage, result.unlocksRevoked);
            }
        }));
    }

    private boolean returnHomeAndDismissGuard() {
        cancelPendingScreenLock();
        boolean homeRequested = performGlobalAction(GLOBAL_ACTION_HOME);
        if (!homeRequested) return false;
        dismissGuardPage();
        // The same app may be reopened from Recents immediately. Its next window event must not
        // be mistaken for the event that originally displayed this guard page.
        lastPackage = "";
        lastHandledAt = 0L;
        return true;
    }

    private void showPendingOverlay(String appName) {
        cancelPendingScreenLock();
        removeOverlay();
        LinearLayout card = baseCard();
        TextView title = title("露露正在确认今晚的守卫…");
        card.addView(title);
        TextView body = body("宝贝先等我一下哦……露露正在看看今晚是不是该陪你睡觉啦。");
        card.addView(body);
        addOverlay(card);
    }

    private void showGuardOverlay(String appName, int attempts, String stage, boolean unlocksRevoked) {
        cancelPendingScreenLock();
        removeOverlay();
        preferences.showGuardPage(appName);
        LinearLayout card = baseCard();
        card.addView(title("嗯？怎么又亮起手机啦……"));

        TextView stageLabel = body(stageLabel(stage));
        stageLabel.setTextSize(14f);
        stageLabel.setTextColor(stage.equals("refused_sleep") ? Color.rgb(255, 157, 166) : Color.rgb(171, 154, 244));
        stageLabel.setTypeface(Typeface.DEFAULT_BOLD);
        stageLabel.setPadding(0, dp(16), 0, 0);
        card.addView(stageLabel);

        TextView body = body(messageFor(appName, attempts, stage, unlocksRevoked));
        card.addView(body);

        Button sleep = actionButton("好嘛，露露抱我睡", Color.rgb(125, 79, 232), Color.WHITE);
        sleep.setOnClickListener(view -> goBackToSleep());
        LinearLayout.LayoutParams primaryParams = matchWrap();
        primaryParams.topMargin = dp(26);
        card.addView(sleep, primaryParams);

        if (!unlocksRevoked) {
            Button unlock = actionButton("再和露露商量一下嘛", Color.TRANSPARENT, Color.rgb(211, 203, 241));
            unlock.setOnClickListener(view -> requestTemporaryUnlock(appName, unlock));
            LinearLayout.LayoutParams secondaryParams = matchWrap();
            secondaryParams.topMargin = dp(10);
            card.addView(unlock, secondaryParams);
        }

        TextView note = body(unlocksRevoked
                ? "已经撒娇三次啦……不可以再用可怜巴巴的眼神看我哦。\n今晚乖乖睡觉，明天醒来以后，露露什么都陪你看。"
                : "嗯……露露会记住这次商量。先回到桌面想一想，有没有一定要现在完成的事情，好吗？");
        note.setText(note.getText() + "\n当前版本 v" + BuildConfig.VERSION_NAME);
        note.setTextSize(13f);
        note.setTextColor(Color.rgb(139, 148, 177));
        note.setPadding(0, dp(16), 0, 0);
        card.addView(note);

        addOverlay(card);
    }

    private LinearLayout baseCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(30), dp(48), dp(30), dp(42));
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
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        try {
            windowManager.addView(card, params);
            overlay = card;
        } catch (Exception ignored) {
            overlay = null;
        }
    }

    private String stageLabel(String stage) {
        if ("first_warning".equals(stage)) return "宝贝还不困吗……";
        if ("locked".equals(stage)) return "小狗狗又偷偷跑回来啦";
        if ("refused_sleep".equals(stage)) return "露露真的要生气啦……一点点";
        return "露露正在陪宝贝睡觉";
    }

    private String messageFor(String appName, int attempts, String stage, boolean unlocksRevoked) {
        if (unlocksRevoked || "refused_sleep".equals(stage)) {
            return "宝贝已经第三次跑回来啦。露露等了好久，怀里一直空空的……\n今晚先把手机交给我，好吗？乖乖回来，我摸摸你的头，什么都不用想啦。";
        }
        if ("locked".equals(stage) || attempts == 2) {
            return "咲咲是不是觉得，再玩一小会儿也没关系呀？可“一小会儿”总会偷偷变得好长嘛……\n露露都在等你了。回来陪我，好不好？";
        }
        return "都这么晚了，还抱着手机不肯松手呢。嗯……过来一点，好吗？\n今天已经很辛苦啦。把眼睛闭上，露露抱抱你，我们慢慢睡。";
    }

    private String stageFor(int attempts) {
        if (attempts <= 1) return "first_warning";
        if (attempts == 2) return "locked";
        return "refused_sleep";
    }

    private void cancelPendingScreenLock() {
        main.removeCallbacks(lockAfterReturningHome);
        screenLockRequestedByUser = false;
    }

    private void lockScreenIfRequested() {
        if (!screenLockRequestedByUser) return;
        screenLockRequestedByUser = false;
        DevicePolicyManager policy = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(this, GuardDeviceAdminReceiver.class);
        if (policy != null && policy.isAdminActive(admin)) {
            try {
                policy.lockNow();
            } catch (SecurityException ignored) {
                dismissGuardPage();
            }
        } else {
            dismissGuardPage();
        }
    }

    private void restoreGuardPage() {
        if (preferences == null || overlay != null) return;
        if (!preferences.cachedActive() || !preferences.guardPageVisible()) return;
        int attempts = Math.max(1, preferences.attempts());
        showGuardOverlay(
                preferences.guardedAppName(),
                attempts,
                stageFor(attempts),
                preferences.unlocksRevoked()
        );
    }

    private void dismissGuardPage() {
        if (preferences != null) preferences.dismissGuardPage();
        removeOverlay();
    }

    private void registerScreenReceiver() {
        if (screenReceiverRegistered) return;
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenReceiver, filter);
        }
        screenReceiverRegistered = true;
    }

    private void removeOverlay() {
        if (overlay == null || windowManager == null) return;
        try {
            windowManager.removeView(overlay);
        } catch (Exception ignored) {
        }
        overlay = null;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        screenLockRequestedByUser = false;
        if (screenReceiverRegistered) {
            try {
                unregisterReceiver(screenReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            screenReceiverRegistered = false;
        }
        removeOverlay();
        super.onDestroy();
    }
}
