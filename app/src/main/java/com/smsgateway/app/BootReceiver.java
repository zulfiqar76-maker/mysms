package com.smsgateway.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            SharedPreferences prefs = context.getSharedPreferences("SmsGateway", Context.MODE_PRIVATE);
            if (prefs.getBoolean("service_running", false)) {
                Log.d("SmsGateway", "Auto-starting service after boot");
                context.startForegroundService(new Intent(context, SmsPollingService.class));
            }
        }
    }
}
