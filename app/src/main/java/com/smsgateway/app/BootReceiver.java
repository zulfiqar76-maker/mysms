package com.smsgateway.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            SharedPreferences prefs = context.getSharedPreferences("SmsGateway", Context.MODE_PRIVATE);
            if (prefs.getBoolean("service_running", false)) {
                context.startForegroundService(new Intent(context, SmsPollingService.class));
            }
        }
    }
}
