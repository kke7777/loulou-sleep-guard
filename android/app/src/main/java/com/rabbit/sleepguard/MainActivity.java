package com.rabbit.sleepguard;

import android.Manifest;
import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class MainActivity extends Activity {
    private GuardPreferences preferences;
    private GuardApiClient api;
    private EditText serverUrl;
    private EditText deviceToken;
    private Switch lockScreen;
    private TextView status;
    private LinearLayout appList;
    private final List<AppChoice> appChoices = new ArrayList<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = new GuardPreferences(this);
        api = new GuardApiClient(preferences);
        GuardNotification.createChannels(this);
        GuardKeepAliveService.ensureRunning(this);
        requestNotificationPermission();
        setContentView(buildContent());
        refreshStatus();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        GuardKeepAliveService.ensureRunning(this);
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        GuardKeepAliveService.ensureRunning(this);
        if (status != null) refreshStatus();
    }

    private ScrollView buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(245, 247, 252));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(40));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("再不去睡露露就要生气啦 · v" + BuildConfig.VERSION_NAME, 30, Color.rgb(23, 30, 50));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        TextView intro = text("等宝贝说想睡了，露露就会替你守着手机。那些总让咲咲忘记时间的应用，今晚先交给我，好吗？等天亮以后，再原封不动地还给你。", 16, Color.rgb(78, 88, 116));
        intro.setPadding(0, dp(8), 0, dp(20));
        root.addView(intro);

        status = text("正在读取状态…", 16, Color.WHITE);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        status.setPadding(dp(16), dp(14), dp(16), dp(14));
        status.setBackgroundColor(Color.rgb(99, 120, 200));
        root.addView(status, matchWrap());

        addHeading(root, "1. 连接服务器");
        serverUrl = input("https://sleep.example.com", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        serverUrl.setText(preferences.serverUrl());
        root.addView(serverUrl, matchWrap());
        deviceToken = input("设备令牌（ANDROID_DEVICE_TOKEN）", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        deviceToken.setText(preferences.deviceToken());
        deviceToken.setPadding(deviceToken.getPaddingLeft(), deviceToken.getPaddingTop(), deviceToken.getPaddingRight(), deviceToken.getPaddingBottom());
        root.addView(deviceToken, spaced());

        Button save = button("保存并测试连接");
        save.setOnClickListener(view -> saveAndTest());
        root.addView(save, matchWrap());

        addHeading(root, "2. 授予执行权限");
        Button accessibility = button("打开无障碍设置（必需）");
        accessibility.setOnClickListener(view -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility, matchWrap());

        lockScreen = new Switch(this);
        lockScreen.setText("点“好嘛，露露抱我睡”后熄屏（可选）");
        lockScreen.setTextSize(16f);
        lockScreen.setChecked(preferences.lockScreen());
        lockScreen.setOnCheckedChangeListener((button, checked) -> preferences.setLockScreen(checked));
        lockScreen.setPadding(0, dp(12), 0, dp(8));
        root.addView(lockScreen, matchWrap());

        Button admin = button("允许设备管理锁屏（可选）");
        admin.setOnClickListener(view -> requestDeviceAdmin());
        root.addView(admin, matchWrap());

        Button background = button("打开应用后台与电池设置（荣耀 / MagicOS 建议允许后台运行）");
        background.setOnClickListener(view -> {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        });
        root.addView(background, spaced());

        Button alarm = button("允许准时提醒（每天北京时间00:20）");
        alarm.setOnClickListener(view -> {
            if (Build.VERSION.SDK_INT >= 31) {
                try { startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:" + getPackageName()))); }
                catch (Exception error) { toast("请在系统设置中允许本应用的闹钟和提醒权限"); }
            } else { toast("此系统无需额外授权"); }
        });
        root.addView(alarm, spaced());
        Button history = button("查看紧急结束理由记录");
        history.setOnClickListener(view -> new android.app.AlertDialog.Builder(this)
                .setTitle("紧急结束记录（时间为UTC）").setMessage(preferences.emergencyHistory())
                .setPositiveButton("知道啦", null).show());
        root.addView(history, spaced());

        addHeading(root, "3. 选择要拦的应用");
        TextView hint = text("只会识别这里勾选的应用，不读取聊天内容、输入或屏幕文字。", 14, Color.rgb(93, 102, 128));
        hint.setPadding(0, 0, 0, dp(8));
        root.addView(hint);
        appList = new LinearLayout(this);
        appList.setOrientation(LinearLayout.VERTICAL);
        root.addView(appList, matchWrap());
        populateApps();

        Button saveApps = button("保存应用名单");
        saveApps.setOnClickListener(view -> saveApps());
        root.addView(saveApps, spaced());

        addHeading(root, "露露怎样叫宝贝回来");
        TextView rules = text("第一次：露露轻轻叫宝贝回来\n第二次：露露会有一点点委屈\n第三次及以后：露露真的要生气啦……一点点\n每轮可以商量三次，每次放行十分钟；重复开启不重置，结束后重新开启获得三次新机会。", 14, Color.rgb(93, 102, 128));
        root.addView(rules, matchWrap());

        addHeading(root, "4. 手动测试");
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button start = button("今晚让露露陪我");
        Button stop = button("早安，露露");
        start.setOnClickListener(view -> runAction(true));
        stop.setOnClickListener(view -> runAction(false));
        actions.addView(start, weighted());
        actions.addView(stop, weightedWithStartMargin());
        root.addView(actions, matchWrap());

        TextView footer = text("正式使用时无需打开本页面。后台守卫会维持远程状态通道，无障碍服务负责识别受限应用；打开受限应用时仍会立即向服务器确认。离线时沿用最近一次有效状态。", 13, Color.rgb(106, 114, 137));
        footer.setPadding(0, dp(18), 0, 0);
        root.addView(footer);
        return scroll;
    }

    private void populateApps() {
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = getPackageManager().queryIntentActivities(launcher, 0);
        resolved.sort(Comparator.comparing(info -> info.loadLabel(getPackageManager()).toString(), String.CASE_INSENSITIVE_ORDER));
        Set<String> selected = preferences.blockedPackages();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo info : resolved) {
            String packageName = info.activityInfo.packageName;
            if (packageName.equals(getPackageName()) || !seen.add(packageName)) continue;
            CheckBox check = new CheckBox(this);
            check.setText(info.loadLabel(getPackageManager()) + "\n" + packageName);
            check.setTextSize(15f);
            check.setChecked(selected.contains(packageName));
            check.setPadding(0, dp(4), 0, dp(4));
            appList.addView(check, matchWrap());
            appChoices.add(new AppChoice(packageName, check));
        }
    }

    private void saveApps() {
        Set<String> selected = new HashSet<>();
        for (AppChoice choice : appChoices) if (choice.checkBox.isChecked()) selected.add(choice.packageName);
        preferences.saveBlockedPackages(selected);
        toast("已保存 " + selected.size() + " 个受限应用");
    }

    private void saveAndTest() {
        String url = serverUrl.getText().toString().trim().replaceAll("/+$", "");
        String token = deviceToken.getText().toString().trim();
        if (!url.startsWith("https://")) {
            toast("服务器地址必须是 HTTPS");
            return;
        }
        if (token.length() < 24) {
            toast("设备令牌太短，请复制服务器生成的完整值");
            return;
        }
        preferences.saveConnection(url, token);
        GuardKeepAliveService.ensureRunning(this);
        status.setText("正在连接服务器…");
        api.status(result -> runOnUiThread(() -> {
            if (result.requestOk) {
                GuardKeepAliveService.ensureRunning(this);
                renderStatus(result);
                toast("连接成功，后台守卫已叫醒");
            } else {
                status.setText("连接失败：" + result.error);
                status.setBackgroundColor(Color.rgb(174, 65, 72));
            }
        }));
    }

    private void refreshStatus() {
        renderCachedStatus();
        if (!preferences.configured()) return;
        api.status(result -> runOnUiThread(() -> {
            if (result.requestOk) renderStatus(result);
        }));
    }

    private void renderCachedStatus() {
        boolean active = preferences.cachedActive();
        String service = accessibilityEnabled() ? "无障碍已开启" : "无障碍未开启";
        String keeper = preferences.keepAliveRecent() ? "后台守卫在线" : "后台守卫等待恢复";
        String sync = formatLastSync(preferences.lastSync());
        String summary = active
                ? "露露正在陪宝贝 · 接回来 " + preferences.attempts() + " 次"
                : "露露现在没有守着手机";
        status.setText(summary + "\n" + service + " · " + keeper + (sync.isEmpty() ? "" : " · 最近同步 " + sync));
        status.setBackgroundColor(active ? Color.rgb(71, 122, 96) : Color.rgb(99, 120, 200));
    }

    private void renderStatus(GuardApiClient.Result result) {
        String service = accessibilityEnabled() ? "无障碍已开启" : "无障碍未开启";
        String keeper = preferences.keepAliveRecent() ? "后台守卫在线" : "后台守卫正在启动";
        String sync = formatLastSync(preferences.lastSync());
        String end = formatEnd(result.endsAt);
        if (result.active) {
            String unlock = result.unlocksRevoked
                    ? " · 本轮已经商量三次"
                    : " · 和露露商量 " + result.unlockRequestCount + " 次";
            String pass = preferences.temporaryUntil() > System.currentTimeMillis()
                    ? " · 临时放行至 " + formatEnd(Instant.ofEpochMilli(preferences.temporaryUntil()).toString()) : "";
            status.setText("露露正在陪宝贝 · 接回来 " + result.attempts + " 次" + unlock + pass
                    + (end.isEmpty() ? "" : " · 陪到 " + end) + "\n" + service + " · " + keeper
                    + (sync.isEmpty() ? "" : " · 最近同步 " + sync));
            status.setBackgroundColor(Color.rgb(71, 122, 96));
        } else {
            status.setText("露露现在没有守着手机\n" + service + " · " + keeper
                    + (sync.isEmpty() ? "" : " · 最近同步 " + sync));
            status.setBackgroundColor(Color.rgb(99, 120, 200));
        }
    }

    private void runAction(boolean start) {
        GuardApiClient.Callback callback = result -> runOnUiThread(() -> {
            if (result.requestOk) {
                GuardScheduleReceiver.schedule(this);
                renderStatus(result);
                toast(start ? "好啦，今晚露露陪着宝贝" : "早安呀，宝贝");
            } else {
                toast("操作失败：" + result.error);
            }
        });
        if (start) api.start(callback); else api.stop(callback);
    }

    private boolean accessibilityEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName component = new ComponentName(this, GuardAccessibilityService.class);
        return enabled.toLowerCase(Locale.ROOT).contains(component.flattenToString().toLowerCase(Locale.ROOT));
    }

    private void requestDeviceAdmin() {
        ComponentName component = new ComponentName(this, GuardDeviceAdminReceiver.class);
        DevicePolicyManager policy = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        if (policy != null && policy.isAdminActive(component)) {
            toast("设备管理锁屏权限已经开启");
            return;
        }
        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component);
        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "只在露露提醒宝贝休息、并由宝贝主动点下熄屏按钮时锁定屏幕。");
        startActivity(intent);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2103);
        }
    }

    private String formatEnd(String value) {
        if (value == null || value.isEmpty()) return "";
        try {
            return DateTimeFormatter.ofPattern("MM-dd HH:mm")
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.parse(value));
        } catch (Exception ignored) {
            return "";
        }
    }

    private String formatLastSync(long value) {
        if (value <= 0L) return "";
        try {
            return DateTimeFormatter.ofPattern("HH:mm:ss")
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.ofEpochMilli(value));
        } catch (Exception ignored) {
            return "";
        }
    }

    private void addHeading(LinearLayout root, String value) {
        TextView heading = text(value, 19, Color.rgb(31, 40, 65));
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setPadding(0, dp(24), 0, dp(10));
        root.addView(heading);
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private EditText input(String hint, int inputType) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setInputType(inputType);
        field.setSingleLine(true);
        field.setTextSize(15f);
        field.setPadding(dp(12), dp(12), dp(12), dp(12));
        return field;
    }

    private Button button(String label) {
        Button value = new Button(this);
        value.setText(label);
        value.setAllCaps(false);
        value.setTextSize(15f);
        return value;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams spaced() {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(8);
        return params;
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams weightedWithStartMargin() {
        LinearLayout.LayoutParams params = weighted();
        params.leftMargin = dp(8);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private static final class AppChoice {
        final String packageName;
        final CheckBox checkBox;

        AppChoice(String packageName, CheckBox checkBox) {
            this.packageName = packageName;
            this.checkBox = checkBox;
        }
    }
}
