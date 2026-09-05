package com.rabbit.sleepguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class GuardRestartReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        new GuardPreferences(context).clock();
        GuardScheduleReceiver.schedule(context);
        GuardKeepAliveService.ensureRunning(context);
    }
}
