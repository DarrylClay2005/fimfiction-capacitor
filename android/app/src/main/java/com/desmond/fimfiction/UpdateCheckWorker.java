package com.desmond.fimfiction;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.TimeUnit;

public class UpdateCheckWorker extends Worker {
    public static final String UNIQUE_WORK_NAME = "update-check-work";
    private static final String CHANNEL_ID = "updates";
    private static final String CHANNEL_NAME = "App Updates";
    private static final String CHANNEL_DESC = "Notifications for new app releases";
    private static final String GH_API_LATEST = "https://api.github.com/repos/DarrylClay2005/fimfiction-capacitor/releases/latest";

    public UpdateCheckWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            if (!hasNetwork()) return Result.retry();

            String currentVersion = "0.0.0";
            try {
                PackageManager pm = getApplicationContext().getPackageManager();
                PackageInfo pi = (Build.VERSION.SDK_INT >= 33)
                        ? pm.getPackageInfo(getApplicationContext().getPackageName(), PackageManager.PackageInfoFlags.of(0))
                        : pm.getPackageInfo(getApplicationContext().getPackageName(), 0);
                if (pi.versionName != null) currentVersion = pi.versionName;
            } catch (Exception ignored) {}

            HttpURLConnection conn = (HttpURLConnection) new URL(GH_API_LATEST).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", getApplicationContext().getPackageName());
            int code = conn.getResponseCode();
            if (code == 200) {
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line; while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                JSONObject obj = new JSONObject(sb.toString());
                String tag = obj.optString("tag_name", "");
                String latest = tag.startsWith("v") ? tag.substring(1) : tag;
                String body = obj.optString("body", "");

                String apkUrl = null;
                JSONArray assets = obj.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.optJSONObject(i);
                        if (a == null) continue;
                        String name = a.optString("name", "");
                        String ctype = a.optString("content_type", "");
                        String bdl = a.optString("browser_download_url", "");
                        if ((name != null && name.endsWith(".apk")) || (ctype != null && ctype.contains("android.package-archive"))) {
                            apkUrl = bdl;
                            break;
                        }
                    }
                }

                if (isNewer(latest, currentVersion)) {
                    notifyUpdate(latest, apkUrl, body);
                }
            }
            conn.disconnect();
            return Result.success();
        } catch (Exception e) {
            return Result.retry();
        }
    }

    private boolean hasNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.net.Network n = cm.getActiveNetwork();
                if (n == null) return false;
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            } else {
                android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
                return ni != null && ni.isConnected();
            }
        } catch (Throwable t) { return false; }
    }

    private boolean isNewer(String a, String b) {
        try {
            String[] pa = a.split("\\.");
            String[] pb = b.split("\\.");
            int max = Math.max(pa.length, pb.length);
            for (int i = 0; i < max; i++) {
                int va = i < pa.length ? Integer.parseInt(pa[i]) : 0;
                int vb = i < pb.length ? Integer.parseInt(pb[i]) : 0;
                if (va != vb) return va > vb;
            }
            return false;
        } catch (Exception e) { return !a.equals(b); }
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription(CHANNEL_DESC);
            NotificationManager nm = (NotificationManager) getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private void notifyUpdate(String latest, String apkUrl, String body) {
        ensureChannel();
        NotificationCompat.Builder nb = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Update available: " + latest)
                .setContentText("Tap to view What's New")
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body != null && !body.isEmpty() ? body : "A new version is available."))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true);
        // Open the app to show What's New dialog
        android.content.Intent intent = new android.content.Intent(getApplicationContext(), com.desmond.fimfiction.MainActivity.class);
        intent.setFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP | android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("whats_new_version", latest);
        intent.putExtra("whats_new_body", body);
        android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                getApplicationContext(), 0, intent,
                android.os.Build.VERSION.SDK_INT >= 31
                        ? android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE
                        : android.app.PendingIntent.FLAG_UPDATE_CURRENT);
        nb.setContentIntent(pi);
        NotificationManagerCompat.from(getApplicationContext()).notify(2001, nb.build());
    }

    public static void schedule(Context ctx) {
        Constraints c = new Constraints.Builder()
                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                .build();
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(UpdateCheckWorker.class, 12, TimeUnit.HOURS)
                .setConstraints(c)
                .build();
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                req
        );
    }
}

