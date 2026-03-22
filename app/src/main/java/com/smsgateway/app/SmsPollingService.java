package com.smsgateway.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
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

    private static final String TAG = "SmsGateway";
    private static final String CHANNEL_ID = "sms_gateway";

    private ScheduledExecutorService scheduler;
    private SharedPreferences prefs;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("SmsGateway", MODE_PRIVATE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(1, buildNotification());
        startPolling();
        return START_STICKY;
    }

    private void startPolling() {
        int interval = prefs.getInt("interval", 15);
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                fetchAndSend();
            } catch (Exception e) {
                Log.e(TAG, "Error: " + e.getMessage());
            }
        }, 5, interval, TimeUnit.SECONDS);
    }

    private void fetchAndSend() throws Exception {
        String serverUrl = prefs.getString("server_url", "");
        String secretKey = prefs.getString("secret_key", "");
        if (serverUrl.isEmpty() || secretKey.isEmpty()) return;

        String now = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        prefs.edit().putString("last_poll_time", now).apply();

        String response = httpGet(serverUrl + "?action=pending&key=" + secretKey);
        if (response == null || response.isEmpty()) return;

        JSONObject result = new JSONObject(response);
        if (!result.optString("status").equals("ok")) return;

        JSONArray messages = result.optJSONArray("messages");
        if (messages == null || messages.length() == 0) return;

        Log.d(TAG, "Found " + messages.length() + " messages");

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
                Log.d(TAG, "SMS sent to: " + to);
            }
        }
    }

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
            Log.e(TAG, "SMS failed: " + e.getMessage());
            return false;
        }
    }

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
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 9; Mobile) AppleWebKit/537.36 Chrome/91.0.4472.120 Mobile Safari/537.36");
            conn.setRequestProperty("Accept", "text/html,application/json,*/*");
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");

            if (!storedCookies.isEmpty()) {
                conn.setRequestProperty("Cookie", storedCookies);
            }

            // Collect cookies from response
            int headerIndex = 1;
            while (true) {
                String headerName = conn.getHeaderFieldKey(headerIndex);
                String headerValue = conn.getHeaderField(headerIndex);
                if (headerName == null) break;
                if (headerName.equalsIgnoreCase("Set-Cookie")) {
                    String cookiePart = headerValue.split(";")[0].trim();
                    String cookieName = cookiePart.split("=")[0];
                    if (!storedCookies.contains(cookieName)) {
                        storedCookies = storedCookies.isEmpty() ? cookiePart : storedCookies + "; " + cookiePart;
                    }
                }
                headerIndex++;
            }

            if (conn.getResponseCode() != 200) return null;

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();

            String body = sb.toString().trim();

            // Handle bot/DDoS challenge
            if (body.contains("slowAES") || body.contains("__test") ||
                body.contains("document.cookie") || body.contains("requires Javascript")) {
                Log.d(TAG, "Bot challenge on attempt " + attempt + ", retrying...");
                if (!storedCookies.contains("__test")) {
                    storedCookies = storedCookies.isEmpty() ? "__test=bypass" : storedCookies + "; __test=bypass";
                }
                try { Thread.sleep(1000); } catch (Exception ignored) {}
                conn.disconnect();
                return null;
            }

            // Strip HTML before JSON if needed
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

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "SMS Gateway", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
        return new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("SMS Gateway Running")
            .setContentText("Checking server for messages...")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setOngoing(true)
            .build();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        if (scheduler != null) scheduler.shutdownNow();
        super.onDestroy();
    }
}
