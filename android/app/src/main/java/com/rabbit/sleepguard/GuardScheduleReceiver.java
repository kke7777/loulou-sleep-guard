package com.rabbit.sleepguard;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Device-side clock also works without the VPS/network. */
public final class GuardScheduleReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        new GuardPreferences(context).clock();
        GuardKeepAliveService.ensureRunning(context);
        schedule(context);
    }
    public static void schedule(Context context) {
        GuardPreferences prefs = new GuardPreferences(context);
        if (!prefs.configured()) return;
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        long now = System.currentTimeMillis(), when = prefs.nextStart(now);
        long end = GuardPreferences.millis(prefs.endsAt());
        if (prefs.cachedActive() && end > now) when = Math.min(when, end);
        if (prefs.cachedActive() && prefs.temporaryUntil() > now) when = Math.min(when, prefs.temporaryUntil());
        PendingIntent intent = PendingIntent.getBroadcast(context, 2020,
                new Intent(context, GuardScheduleReceiver.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms())
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, intent);
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, intent);
        } catch (SecurityException denied) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, intent);
        }
    }
}
