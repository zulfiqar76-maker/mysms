package com.smsgateway.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.telephony.SmsManager;
import android.util.Log;

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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class SmsPollingService extends Service {

    private static final String TAG          = "SmsGateway";
    private static final String CHANNEL_ID   = "sms_gateway_channel";
    private static final int    NOTIF_ID     = 1001;

    private ScheduledExecutorService scheduler;
    private SharedPreferences        prefs;
    private PowerManager.WakeLock    wakeLock;

    // ─────────────────────────────────────────────────────────────
    //  SERVICE LIFECYCLE
    // ─────────────────────────────────────────────────────────────
    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("SmsGateway", MODE_PRIVATE);

        // Acquire WakeLock — keeps CPU running even when screen is off
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SmsGateway::PollingWakeLock"
        );
        wakeLock.acquire();

        Log.d(TAG, "Service created — WakeLock acquired");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Start as foreground service with notification
        startForeground(NOTIF_ID, buildNotification("Polling server..."));

        // Reset sent count
        prefs.edit().putInt("sms_sent_count", 0).apply();

        // Start polling loop
        startPolling();

        Log.d(TAG, "Service started");

        // START_STICKY — Android will restart service if killed
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service destroyed — releasing WakeLock");

        // Stop polling
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }

        // Release WakeLock
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }

        // Schedule restart via AlarmManager in case Android kills service
        scheduleRestart();

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ─────────────────────────────────────────────────────────────
    //  RESTART IF KILLED BY ANDROID
    // ─────────────────────────────────────────────────────────────
    private void scheduleRestart() {
        boolean shouldRun = prefs.getBoolean("service_running", false);
        if (!shouldRun) return;

        try {
            Intent restartIntent = new Intent(getApplicationContext(), SmsPollingService.class);
            PendingIntent pendingIntent = PendingIntent.getService(
                getApplicationContext(), 1, restartIntent,
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE
            );

            AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (alarmManager != null) {
                alarmManager.set(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 5000, // restart after 5 seconds
                    pendingIntent
                );
                Log.d(TAG, "Restart scheduled in 5 seconds");
            }
        } catch (Exception e) {
            Log.e(TAG, "Could not schedule restart: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  POLLING LOOP
    // ─────────────────────────────────────────────────────────────
    private void startPolling() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }

        int intervalSeconds = prefs.getInt("interval", 15);
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(
            this::pollAndSend,
            2,                  // first run after 2 seconds
            intervalSeconds,    // then every N seconds
            TimeUnit.SECONDS
        );

        Log.d(TAG, "Polling every " + intervalSeconds + " seconds");
    }

    private void pollAndSend() {
        // Re-acquire WakeLock for this poll cycle
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock pollLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SmsGateway::PollWakeLock"
        );
        pollLock.acquire(30000); // max 30 seconds

        try {
            String serverUrl = prefs.getString("server_url", "");
            String secretKey = prefs.getString("secret_key", "");

            if (serverUrl.isEmpty() || secretKey.isEmpty()) return;

            // Save last poll time
            String now = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            prefs.edit().putString("last_poll_time", now).apply();

            // Fetch pending messages
            String response = httpGet(serverUrl + "?action=pending&key=" + secretKey);

            if (response == null || response.isEmpty()) {
                updateNotification("Last check: " + now + " — No server response");
                return;
            }

            // Strip HTML if any
            int jsonStart = response.indexOf('{');
            if (jsonStart > 0) response = response.substring(jsonStart);
            if (jsonStart < 0) {
                updateNotification("Last check: " + now + " — Bad server response");
                return;
            }

            JSONObject result = new JSONObject(response);
            if (!result.optString("status").equals("ok")) return;

            JSONArray messages = result.optJSONArray("messages");
            if (messages == null || messages.length() == 0) {
                updateNotification("Last check: " + now + " — No pending messages");
                return;
            }

            updateNotification("Sending " + messages.length() + " SMS...");

            for (int i = 0; i < messages.length(); i++) {
                JSONObject msg = messages.getJSONObject(i);
                String id   = msg.getString("id");
                String to   = msg.getString("to");
                String text = msg.getString("text");

                boolean sent = sendSms(to, text);
                httpGet(serverUrl + "?action=update&key=" + secretKey
                    + "&id=" + id + "&status=" + (sent ? "sent" : "failed"));

                if (sent) {
                    int count = prefs.getInt("sms_sent_count", 0) + 1;
                    prefs.edit().putInt("sms_sent_count", count).apply();
                    Log.d(TAG, "✅ SMS sent to: " + to);
                    updateNotification("✅ Sent " + count + " SMS — Last: " + now);
                } else {
                    Log.e(TAG, "❌ SMS failed to: " + to);
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Poll error: " + e.getMessage());
        } finally {
            if (pollLock.isHeld()) pollLock.release();
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  SEND SMS
    // ─────────────────────────────────────────────────────────────
    private boolean sendSms(String phoneNumber, String message) {
        try {
            if (!phoneNumber.startsWith("+") && !phoneNumber.startsWith("00")) {
                phoneNumber = "+" + phoneNumber;
            }

            SmsManager smsManager = SmsManager.getDefault();
            ArrayList<String> parts = smsManager.divideMessage(message);

            if (parts.size() > 1) {
                smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null);
            } else {
                smsManager.sendTextMessage(phoneNumber, null, message, null, null);
            }
            return true;

        } catch (Exception e) {
            Log.e(TAG, "SMS send error: " + e.getMessage());
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  HTTP GET with cookie bypass
    // ─────────────────────────────────────────────────────────────
    private String storedCookies = "";

    private String httpGet(String urlString) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            String result = httpGetOnce(urlString, attempt);
            if (result != null) return result;
        }
        return null;
    }

    private String httpGetOnce(String urlString, int attempt) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(20000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 9; Mobile) AppleWebKit/537.36 Chrome/91.0 Mobile Safari/537.36");
            conn.setRequestProperty("Accept", "application/json,*/*");

            if (!storedCookies.isEmpty()) {
                conn.setRequestProperty("Cookie", storedCookies);
            }

            // Collect cookies
            int hi = 1;
            while (true) {
                String hn = conn.getHeaderFieldKey(hi);
                String hv = conn.getHeaderField(hi);
                if (hn == null) break;
                if (hn.equalsIgnoreCase("Set-Cookie")) {
                    String cp = hv.split(";")[0].trim();
                    String cn = cp.split("=")[0];
                    if (!storedCookies.contains(cn)) {
                        storedCookies = storedCookies.isEmpty() ? cp : storedCookies + "; " + cp;
                    }
                }
                hi++;
            }

            if (conn.getResponseCode() != 200) return null;

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();

            String body = sb.toString().trim();

            // Handle bot challenge
            if (body.contains("slowAES") || body.contains("document.cookie") ||
                body.contains("__test") || body.contains("requires Javascript")) {
                if (!storedCookies.contains("__test")) {
                    storedCookies = storedCookies.isEmpty() ? "__test=bypass" : storedCookies + "; __test=bypass";
                }
                try { Thread.sleep(1000); } catch (Exception ignored) {}
                return null;
            }

            // Strip HTML before JSON
            int jsonStart = body.indexOf('{');
            if (jsonStart > 0) body = body.substring(jsonStart);

            return body;

        } catch (Exception e) {
            Log.e(TAG, "HTTP error attempt " + attempt + ": " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  NOTIFICATION
    // ─────────────────────────────────────────────────────────────
    private void buildChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "SMS Gateway", NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("SMS Gateway background service");
            channel.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        buildChannel();
        return new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("📱 SMS Gateway Active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setOngoing(true)
            .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_ID, buildNotification(text));
        }
        // Also save to prefs so MainActivity can show it
        prefs.edit().putString("last_log", text).apply();
    }
}
