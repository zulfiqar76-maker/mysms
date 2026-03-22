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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int SMS_PERMISSION_CODE = 100;

    // UI elements
    private EditText etServerUrl, etSecretKey, etInterval;
    private EditText etTestPhone, etTestMessage;
    private TextView tvStatus, tvAbout, tvPermission;
    private TextView tvLastPoll, tvSmsSent, tvLastLog, tvTestResult;
    private Button btnSave, btnStartStop, btnTestConnection;
    private Button btnFixPermission, btnSendTest;

    private SharedPreferences prefs;

    // Polling handler - runs in foreground (fixes Android 9 background SMS block)
    private Handler pollHandler = new Handler();
    private Runnable pollRunnable;
    private boolean isPolling = false;
    private int smsSentCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Connect views
        etServerUrl      = findViewById(R.id.etServerUrl);
        etSecretKey      = findViewById(R.id.etSecretKey);
        etInterval       = findViewById(R.id.etInterval);
        etTestPhone      = findViewById(R.id.etTestPhone);
        etTestMessage    = findViewById(R.id.etTestMessage);
        tvStatus         = findViewById(R.id.tvStatus);
        tvAbout          = findViewById(R.id.tvAbout);
        tvPermission     = findViewById(R.id.tvPermission);
        tvLastPoll       = findViewById(R.id.tvLastPoll);
        tvSmsSent        = findViewById(R.id.tvSmsSent);
        tvLastLog        = findViewById(R.id.tvLastLog);
        tvTestResult     = findViewById(R.id.tvTestResult);
        btnSave          = findViewById(R.id.btnSave);
        btnStartStop     = findViewById(R.id.btnStartStop);
        btnTestConnection = findViewById(R.id.btnTestConnection);
        btnFixPermission = findViewById(R.id.btnFixPermission);
        btnSendTest      = findViewById(R.id.btnSendTest);

        prefs = getSharedPreferences("SmsGateway", MODE_PRIVATE);

        // Load saved settings
        etServerUrl.setText(prefs.getString("server_url", ""));
        etSecretKey.setText(prefs.getString("secret_key", ""));
        etInterval.setText(String.valueOf(prefs.getInt("interval", 15)));

        // About info
        tvAbout.setText(
            "App Version: 5.0\n" +
            "Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n" +
            "Device: " + Build.MANUFACTURER + " " + Build.MODEL + "\n" +
            "Brand: " + Build.BRAND
        );

        updatePermissionUI();

        // Button listeners
        btnSave.setOnClickListener(v -> saveSettings());
        btnStartStop.setOnClickListener(v -> togglePolling());
        btnTestConnection.setOnClickListener(v -> testConnection());
        btnSendTest.setOnClickListener(v -> sendTestSms());
        btnFixPermission.setOnClickListener(v ->
            ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.SEND_SMS}, SMS_PERMISSION_CODE)
        );

        // Request SMS permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.SEND_SMS}, SMS_PERMISSION_CODE);
        }

        // Auto restart if was running
        if (prefs.getBoolean("service_running", false)) {
            startPolling();
        }

        updateStatusUI();
    }

    // ═══════════════════════════════════════════════════════════
    //  POLLING — runs in foreground Handler (fixes TECNO Android 9)
    // ═══════════════════════════════════════════════════════════
    private void startPolling() {
        isPolling = true;
        smsSentCount = 0;
        prefs.edit().putBoolean("service_running", true).apply();

        int intervalSeconds = prefs.getInt("interval", 15);

        pollRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isPolling) return;

                // Run network + SMS in background thread
                new Thread(() -> {
                    try {
                        fetchAndSendMessages();
                    } catch (Exception e) {
                        updateLog("Error: " + e.getMessage());
                    }
                }).start();

                // Schedule next poll
                pollHandler.postDelayed(this, intervalSeconds * 1000L);
            }
        };

        // Start first poll after 2 seconds
        pollHandler.postDelayed(pollRunnable, 2000);
        updateStatusUI();
        updateLog("Polling started every " + intervalSeconds + "s");
    }

    private void stopPolling() {
        isPolling = false;
        prefs.edit().putBoolean("service_running", false).apply();
        if (pollRunnable != null) {
            pollHandler.removeCallbacks(pollRunnable);
        }
        updateStatusUI();
        updateLog("Polling stopped");
    }

    private void togglePolling() {
        if (isPolling) {
            stopPolling();
            Toast.makeText(this, "Service stopped", Toast.LENGTH_SHORT).show();
        } else {
            String url = prefs.getString("server_url", "");
            String key = prefs.getString("secret_key", "");
            if (url.isEmpty() || key.isEmpty()) {
                Toast.makeText(this, "Save your settings first!", Toast.LENGTH_SHORT).show();
                return;
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                    != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "SMS permission required!", Toast.LENGTH_SHORT).show();
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.SEND_SMS}, SMS_PERMISSION_CODE);
                return;
            }
            startPolling();
            Toast.makeText(this, "✅ Service started!", Toast.LENGTH_SHORT).show();
            askDisableBatteryOptimization();
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  FETCH MESSAGES FROM SERVER AND SEND SMS
    // ═══════════════════════════════════════════════════════════
    private void fetchAndSendMessages() throws Exception {
        String serverUrl = prefs.getString("server_url", "");
        String secretKey = prefs.getString("secret_key", "");

        if (serverUrl.isEmpty() || secretKey.isEmpty()) return;

        // Update last poll time
        String now = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        runOnUiThread(() -> tvLastPoll.setText("Last server check: " + now));

        // Fetch pending messages
        String rawResponse = httpGet(serverUrl + "?action=pending&key=" + secretKey);

        if (rawResponse == null || rawResponse.isEmpty()) {
            updateLog(now + " - No response from server");
            return;
        }

        // Show raw response for debugging
        updateLog("RAW: " + rawResponse.substring(0, Math.min(rawResponse.length(), 80)));

        // Strip any HTML/whitespace before JSON
        String response = rawResponse;
        int jsonStart = rawResponse.indexOf('{');
        if (jsonStart > 0) {
            response = rawResponse.substring(jsonStart);
            updateLog("Stripped " + jsonStart + " chars of HTML before JSON");
        } else if (jsonStart < 0) {
            updateLog("ERROR: No JSON in response! Server returned HTML only");
            return;
        }

        JSONObject result = new JSONObject(response);
        if (!result.optString("status").equals("ok")) {
            updateLog("Server error: " + result.optString("message"));
            return;
        }

        JSONArray messages = result.optJSONArray("messages");
        if (messages == null || messages.length() == 0) {
            updateLog(now + " - No pending messages");
            return;
        }

        updateLog(now + " - Found " + messages.length() + " message(s) to send!");

        for (int i = 0; i < messages.length(); i++) {
            JSONObject msg = messages.getJSONObject(i);
            String id   = msg.getString("id");
            String to   = msg.getString("to");
            String text = msg.getString("text");

            updateLog("Sending to: " + to);

            boolean sent = sendSmsNow(to, text);

            // Mark as sent on server
            httpGet(serverUrl + "?action=update&key=" + secretKey
                + "&id=" + id + "&status=" + (sent ? "sent" : "failed"));

            if (sent) {
                smsSentCount++;
                final int count = smsSentCount;
                runOnUiThread(() -> tvSmsSent.setText("SMS Sent this session: " + count));
                updateLog("✅ SMS sent to " + to);
            } else {
                updateLog("❌ SMS FAILED to " + to);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  SEND SMS — works on Android 9 TECNO
    // ═══════════════════════════════════════════════════════════
    private boolean sendSmsNow(String phoneNumber, String message) {
        try {
            // Ensure country code
            if (!phoneNumber.startsWith("+") && !phoneNumber.startsWith("00")) {
                phoneNumber = "+" + phoneNumber;
            }

            SmsManager smsManager = SmsManager.getDefault();

            ArrayList<String> parts = smsManager.divideMessage(message);
            if (parts.size() > 1) {
                smsManager.sendMultipartTextMessage(
                    phoneNumber, null, parts, null, null
                );
            } else {
                smsManager.sendTextMessage(
                    phoneNumber, null, message, null, null
                );
            }
            return true;

        } catch (Exception e) {
            updateLog("SMS error: " + e.getMessage());
            return false;
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  DIRECT TEST SMS (bypasses server)
    // ═══════════════════════════════════════════════════════════
    private void sendTestSms() {
        String phone   = etTestPhone.getText().toString().trim();
        String message = etTestMessage.getText().toString().trim();

        if (phone.isEmpty()) {
            tvTestResult.setText("❌ Enter a phone number first");
            tvTestResult.setTextColor(0xFFCC0000);
            return;
        }
        if (message.isEmpty()) message = "SMS Gateway Test - " + new Date();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            tvTestResult.setText("❌ SMS Permission not granted!");
            tvTestResult.setTextColor(0xFFCC0000);
            return;
        }

        tvTestResult.setText("⏳ Sending...");
        tvTestResult.setTextColor(0xFF888888);

        final String finalPhone   = phone;
        final String finalMessage = message;

        new Thread(() -> {
            boolean ok = sendSmsNow(finalPhone, finalMessage);
            runOnUiThread(() -> {
                if (ok) {
                    tvTestResult.setText("✅ SMS sent to " + finalPhone + "\nCheck your inbox!");
                    tvTestResult.setTextColor(0xFF2E7D32);
                } else {
                    tvTestResult.setText("❌ Failed! Check SMS permission.");
                    tvTestResult.setTextColor(0xFFCC0000);
                }
            });
        }).start();
    }

    // ═══════════════════════════════════════════════════════════
    //  HTTP GET
    // ═══════════════════════════════════════════════════════════
    private String httpGet(String urlString) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "SmsGatewayApp/5.0");

            if (conn.getResponseCode() != 200) return null;

            BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream())
            );
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            return sb.toString();

        } catch (Exception e) {
            updateLog("HTTP error: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  HELPERS
    // ═══════════════════════════════════════════════════════════
    private void updateLog(String msg) {
        runOnUiThread(() -> tvLastLog.setText("Log: " + msg));
    }

    private void updateStatusUI() {
        if (isPolling) {
            tvStatus.setText("🟢 Service is RUNNING — Polling your server");
            tvStatus.setTextColor(0xFF2E7D32);
            btnStartStop.setText("STOP SERVICE");
            btnStartStop.setBackgroundColor(0xFFE53935);
        } else {
            tvStatus.setText("🔴 Service is STOPPED");
            tvStatus.setTextColor(0xFFCC0000);
            btnStartStop.setText("START SERVICE");
            btnStartStop.setBackgroundColor(0xFF43A047);
        }
    }

    private void updatePermissionUI() {
        boolean hasSms = ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                         == PackageManager.PERMISSION_GRANTED;
        if (hasSms) {
            tvPermission.setText("✅ SMS Permission: GRANTED");
            tvPermission.setTextColor(0xFF2E7D32);
            btnFixPermission.setVisibility(android.view.View.GONE);
        } else {
            tvPermission.setText("❌ SMS Permission: DENIED — Tap below to fix!");
            tvPermission.setTextColor(0xFFCC0000);
            btnFixPermission.setVisibility(android.view.View.VISIBLE);
        }
    }

    private void saveSettings() {
        String url = etServerUrl.getText().toString().trim();
        String key = etSecretKey.getText().toString().trim();
        String intervalStr = etInterval.getText().toString().trim();

        if (url.isEmpty() || key.isEmpty()) {
            Toast.makeText(this, "Fill Server URL and Secret Key", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
        }

        int interval = 15;
        try {
            interval = Integer.parseInt(intervalStr);
            if (interval < 5) interval = 5;
            if (interval > 300) interval = 300;
        } catch (Exception ignored) {}

        prefs.edit()
            .putString("server_url", url)
            .putString("secret_key", key)
            .putInt("interval", interval)
            .apply();

        etServerUrl.setText(url);
        Toast.makeText(this, "✅ Settings saved!", Toast.LENGTH_SHORT).show();
    }

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
    protected void onDestroy() {
        // Keep polling alive even when app is in background via service
        if (isPolling) {
            // Start background service as backup
            try {
                startForegroundService(new Intent(this, SmsPollingService.class));
            } catch (Exception ignored) {}
        }
        pollHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
