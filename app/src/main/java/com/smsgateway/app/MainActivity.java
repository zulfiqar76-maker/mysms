package com.smsgateway.app;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.telephony.SmsManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {

    private static final int SMS_PERMISSION_CODE = 100;

    private EditText etServerUrl, etSecretKey, etInterval;
    private EditText etTestPhone, etTestMessage;
    private TextView tvStatus, tvAbout, tvPermission;
    private TextView tvLastPoll, tvSmsSent, tvLastLog, tvTestResult;
    private Button btnSave, btnStartStop, btnTestConnection;
    private Button btnFixPermission, btnSendTest;

    private SharedPreferences prefs;
    private Handler refreshHandler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etServerUrl       = findViewById(R.id.etServerUrl);
        etSecretKey       = findViewById(R.id.etSecretKey);
        etInterval        = findViewById(R.id.etInterval);
        etTestPhone       = findViewById(R.id.etTestPhone);
        etTestMessage     = findViewById(R.id.etTestMessage);
        tvStatus          = findViewById(R.id.tvStatus);
        tvAbout           = findViewById(R.id.tvAbout);
        tvPermission      = findViewById(R.id.tvPermission);
        tvLastPoll        = findViewById(R.id.tvLastPoll);
        tvSmsSent         = findViewById(R.id.tvSmsSent);
        tvLastLog         = findViewById(R.id.tvLastLog);
        tvTestResult      = findViewById(R.id.tvTestResult);
        btnSave           = findViewById(R.id.btnSave);
        btnStartStop      = findViewById(R.id.btnStartStop);
        btnTestConnection = findViewById(R.id.btnTestConnection);
        btnFixPermission  = findViewById(R.id.btnFixPermission);
        btnSendTest       = findViewById(R.id.btnSendTest);

        prefs = getSharedPreferences("SmsGateway", MODE_PRIVATE);

        etServerUrl.setText(prefs.getString("server_url", ""));
        etSecretKey.setText(prefs.getString("secret_key", ""));
        etInterval.setText(String.valueOf(prefs.getInt("interval", 15)));

        tvAbout.setText(
            "App Version: 7.0\n" +
            "Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n" +
            "Device: " + Build.MANUFACTURER + " " + Build.MODEL + "\n" +
            "Mode: Background Service + WakeLock"
        );

        updatePermissionUI();
        updateStatusUI();
        startUIRefresh();

        btnSave.setOnClickListener(v -> saveSettings());
        btnStartStop.setOnClickListener(v -> toggleService());
        btnTestConnection.setOnClickListener(v -> testConnection());
        btnSendTest.setOnClickListener(v -> sendTestSms());
        btnFixPermission.setOnClickListener(v ->
            ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.SEND_SMS}, SMS_PERMISSION_CODE)
        );

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.SEND_SMS}, SMS_PERMISSION_CODE);
        }
    }

    // ── UI REFRESH every 3 seconds from SharedPreferences ────────
    private void startUIRefresh() {
        refreshHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                updateStatusUI();
                updatePermissionUI();
                refreshHandler.postDelayed(this, 3000);
            }
        }, 3000);
    }

    private void updateStatusUI() {
        boolean running = prefs.getBoolean("service_running", false);
        int     smsSent = prefs.getInt("sms_sent_count", 0);
        String lastPoll = prefs.getString("last_poll_time", "Never");
        String  lastLog = prefs.getString("last_log", "Waiting...");

        if (running) {
            tvStatus.setText("🟢 Service is RUNNING — Background Active");
            tvStatus.setTextColor(0xFF2E7D32);
            btnStartStop.setText("STOP SERVICE");
            btnStartStop.setBackgroundColor(0xFFE53935);
        } else {
            tvStatus.setText("🔴 Service is STOPPED");
            tvStatus.setTextColor(0xFFCC0000);
            btnStartStop.setText("START SERVICE");
            btnStartStop.setBackgroundColor(0xFF43A047);
        }

        tvLastPoll.setText("Last server check: " + lastPoll);
        tvSmsSent.setText("SMS Sent this session: " + smsSent);
        tvLastLog.setText("Log: " + lastLog);
    }

    private void updatePermissionUI() {
        boolean hasSms = ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                         == PackageManager.PERMISSION_GRANTED;
        if (hasSms) {
            tvPermission.setText("✅ SMS Permission: GRANTED");
            tvPermission.setTextColor(0xFF2E7D32);
            btnFixPermission.setVisibility(android.view.View.GONE);
        } else {
            tvPermission.setText("❌ SMS Permission: DENIED — Tap below!");
            tvPermission.setTextColor(0xFFCC0000);
            btnFixPermission.setVisibility(android.view.View.VISIBLE);
        }
    }

    // ── TOGGLE SERVICE ────────────────────────────────────────────
    private void toggleService() {
        boolean running = prefs.getBoolean("service_running", false);

        if (running) {
            // Stop service
            stopService(new Intent(this, SmsPollingService.class));
            prefs.edit()
                .putBoolean("service_running", false)
                .putString("last_log", "Service stopped by user")
                .apply();
            Toast.makeText(this, "Service stopped", Toast.LENGTH_SHORT).show();
        } else {
            // Validate settings
            String url = prefs.getString("server_url", "");
            String key = prefs.getString("secret_key", "");
            if (url.isEmpty() || key.isEmpty()) {
                Toast.makeText(this, "Save your settings first!", Toast.LENGTH_SHORT).show();
                return;
            }

            // Check SMS permission
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.SEND_SMS}, SMS_PERMISSION_CODE);
                return;
            }

            // Start background service
            prefs.edit()
                .putBoolean("service_running", true)
                .putInt("sms_sent_count", 0)
                .putString("last_log", "Service starting...")
                .apply();

            Intent serviceIntent = new Intent(this, SmsPollingService.class);
            startForegroundService(serviceIntent);

            Toast.makeText(this, "✅ Background service started!", Toast.LENGTH_SHORT).show();

            // Ask to disable battery optimization
            askDisableBatteryOptimization();
        }

        updateStatusUI();
    }

    // ── SAVE SETTINGS ─────────────────────────────────────────────
    private void saveSettings() {
        String url = etServerUrl.getText().toString().trim();
        String key = etSecretKey.getText().toString().trim();
        String intervalStr = etInterval.getText().toString().trim();

        if (url.isEmpty() || key.isEmpty()) {
            Toast.makeText(this, "Fill Server URL and Secret Key", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }

        int interval = 15;
        try {
            interval = Integer.parseInt(intervalStr);
            if (interval < 5)   interval = 5;
            if (interval > 300) interval = 300;
        } catch (Exception ignored) {}

        prefs.edit()
            .putString("server_url", url)
            .putString("secret_key", key)
            .putInt("interval", interval)
            .apply();

        etServerUrl.setText(url);
        Toast.makeText(this, "✅ Settings saved!", Toast.LENGTH_SHORT).show();

        // Restart service if running to apply new settings
        if (prefs.getBoolean("service_running", false)) {
            stopService(new Intent(this, SmsPollingService.class));
            startForegroundService(new Intent(this, SmsPollingService.class));
        }
    }

    // ── DIRECT TEST SMS ───────────────────────────────────────────
    private void sendTestSms() {
        String phone   = etTestPhone.getText().toString().trim();
        String message = etTestMessage.getText().toString().trim();

        if (phone.isEmpty()) {
            tvTestResult.setText("❌ Enter phone number");
            tvTestResult.setTextColor(0xFFCC0000);
            return;
        }
        if (message.isEmpty()) message = "SMS Gateway Test " + System.currentTimeMillis();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            tvTestResult.setText("❌ SMS Permission not granted!");
            tvTestResult.setTextColor(0xFFCC0000);
            return;
        }

        tvTestResult.setText("⏳ Sending...");
        tvTestResult.setTextColor(0xFF888888);

        final String fp = phone;
        final String fm = message;

        new Thread(() -> {
            boolean ok = false;
            String  err = "";
            try {
                String pn = fp;
                if (!pn.startsWith("+") && !pn.startsWith("00")) pn = "+" + pn;
                SmsManager sm = SmsManager.getDefault();
                ArrayList<String> parts = sm.divideMessage(fm);
                if (parts.size() > 1) {
                    sm.sendMultipartTextMessage(pn, null, parts, null, null);
                } else {
                    sm.sendTextMessage(pn, null, fm, null, null);
                }
                ok = true;
            } catch (Exception e) {
                err = e.getMessage();
            }

            final boolean finalOk  = ok;
            final String  finalErr = err;
            runOnUiThread(() -> {
                if (finalOk) {
                    tvTestResult.setText("✅ Test SMS sent to " + fp + "\nCheck your inbox!");
                    tvTestResult.setTextColor(0xFF2E7D32);
                } else {
                    tvTestResult.setText("❌ Failed: " + finalErr);
                    tvTestResult.setTextColor(0xFFCC0000);
                }
            });
        }).start();
    }

    // ── TEST CONNECTION ───────────────────────────────────────────
    private void testConnection() {
        String url = prefs.getString("server_url", "");
        String key = prefs.getString("secret_key", "");
        if (url.isEmpty()) {
            Toast.makeText(this, "Save settings first!", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(new Intent(Intent.ACTION_VIEW,
            Uri.parse(url + "?action=pending&key=" + key)));
    }

    // ── BATTERY OPTIMIZATION ──────────────────────────────────────
    private void askDisableBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
            @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == SMS_PERMISSION_CODE) {
            updatePermissionUI();
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "✅ SMS permission granted!", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatusUI();
        updatePermissionUI();
    }

    @Override
    protected void onDestroy() {
        refreshHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
        // Service keeps running — do NOT stop it here
    }
}
