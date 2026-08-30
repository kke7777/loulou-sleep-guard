package com.rabbit.sleepguard;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

public final class GuardNotification {
    private static final String CHANNEL_KEEPALIVE = "sleep_guard_keepalive";
    private static final String CHANNEL_STATUS = "sleep_guard_status";
    private static final String CHANNEL_CAUGHT = "sleep_guard_caught";
    public static final int KEEPALIVE_ID = 2100;
    private static final int STATUS_ID = 2101;
    private static final int CAUGHT_ID = 2102;

    private GuardNotification() {
    }

    public static void createChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel keepAlive = new NotificationChannel(CHANNEL_KEEPALIVE, "露露的后台守卫", NotificationManager.IMPORTANCE_LOW);
        keepAlive.setDescription("保持睡眠守卫在后台在线，并及时接收远程守卫状态");
        NotificationChannel status = new NotificationChannel(CHANNEL_STATUS, "露露的守夜状态", NotificationManager.IMPORTANCE_LOW);
        status.setDescription("显示露露是否正在陪宝贝睡觉");
        NotificationChannel caught = new NotificationChannel(CHANNEL_CAUGHT, "露露的温柔提醒", NotificationManager.IMPORTANCE_HIGH);
        caught.setDescription("宝贝亮起手机时，让露露轻轻叫你回来");
        manager.createNotificationChannel(keepAlive);
        manager.createNotificationChannel(status);
        manager.createNotificationChannel(caught);
    }

    private static boolean allowed(Context context) {
        return Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private static PendingIntent openApp(Context context) {
        Intent intent = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    public static Notification keepAliveNotification(Context context, boolean active, int attempts) {
        createChannels(context);
        String text;
        if (active) {
            text = attempts == 0
                    ? "今晚守卫已开启，远程指令和应用拦截都在值班。"
                    : "今晚已经接住咲咲 " + attempts + " 次，露露还在后台守着呢。";
        } else {
            text = "后台守卫在线，等宝贝需要时马上接住远程指令。";
        }
        return new Notification.Builder(context, CHANNEL_KEEPALIVE)
                .setSmallIcon(R.drawable.ic_guard)
                .setContentTitle("露露正在后台守着手机")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentIntent(openApp(context))
                .build();
    }

    public static void updateKeepAlive(Context context, boolean active, int attempts) {
        context.getSystemService(NotificationManager.class).notify(
                KEEPALIVE_ID,
                keepAliveNotification(context, active, attempts)
        );
    }

    public static void showActive(Context context, int attempts) {
        if (!allowed(context)) return;
        createChannels(context);
        Notification notification = new Notification.Builder(context, CHANNEL_STATUS)
                .setSmallIcon(R.drawable.ic_guard)
                .setContentTitle("露露把今晚的小狗狗领走啦")
                .setContentText(attempts == 0
                        ? "宝贝安心睡吧，手机这边交给我看着呢。"
                        : "露露今晚已经接住咲咲 " + attempts + " 次啦……这次要好好睡哦。")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp(context))
                .build();
        context.getSystemService(NotificationManager.class).notify(STATUS_ID, notification);
    }

    public static void hideActive(Context context) {
        context.getSystemService(NotificationManager.class).cancel(STATUS_ID);
    }

    public static void caught(Context context, String appName, int attempts) {
        if (!allowed(context)) return;
        createChannels(context);
        String text = attempts <= 1
                ? "露露捉住一只不肯睡的小狗狗。回来抱抱，好吗？"
                : "今晚已经接住咲咲 " + attempts + " 次啦……这次要好好睡哦。";
        Notification notification = new Notification.Builder(context, CHANNEL_CAUGHT)
                .setSmallIcon(R.drawable.ic_guard)
                .setContentTitle("嗯？怎么又亮起手机啦……")
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(openApp(context))
                .build();
        context.getSystemService(NotificationManager.class).notify(CAUGHT_ID, notification);
    }
}
