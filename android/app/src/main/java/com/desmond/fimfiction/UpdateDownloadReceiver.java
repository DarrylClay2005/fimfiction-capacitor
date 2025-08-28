package com.desmond.fimfiction;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;

public class UpdateDownloadReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(action)) {
            handleDownloadComplete(context, intent);
        } else if ("com.desmond.fimfiction.ACTION_DOWNLOAD_APK".equals(action)) {
            String url = intent.getStringExtra("apk_url");
            String version = intent.getStringExtra("version");
            if (url != null && !url.isEmpty()) {
                startDownload(context, url, version);
            }
        }
    }

    private void startDownload(Context context, String url, String version) {
        try {
            DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) return;
            String fileName = android.webkit.URLUtil.guessFileName(url, null, "application/vnd.android.package-archive");
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setMimeType("application/vnd.android.package-archive");
            req.setTitle(fileName);
            req.setDescription("Downloading update...");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, fileName);
            long id = dm.enqueue(req);
            if (version != null && !version.isEmpty()) {
                SharedPreferences p = context.getSharedPreferences("whats_new", Context.MODE_PRIVATE);
                p.edit().putLong("last_update_download_id", id)
                        .putString("last_update_version", version)
                        .apply();
            }
            Toast.makeText(context, "Downloading update...", Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {}
    }

    private void handleDownloadComplete(Context context, Intent intent) {
        long completeId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
        SharedPreferences p = context.getSharedPreferences("whats_new", Context.MODE_PRIVATE);
        long expectedId = p.getLong("last_update_download_id", -1);
        if (completeId != expectedId || completeId == -1) return;
        try {
            DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) return;
            Uri fileUri = dm.getUriForDownloadedFile(completeId);
            if (fileUri == null) {
                // Try to query
                DownloadManager.Query q = new DownloadManager.Query().setFilterById(completeId);
                Cursor c = dm.query(q);
                if (c != null) {
                    try {
                        if (c.moveToFirst()) {
                            String uriStr = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI));
                            if (uriStr != null) fileUri = Uri.parse(uriStr);
                        }
                    } finally { c.close(); }
                }
            }
            if (fileUri != null) {
                Intent install = new Intent(Intent.ACTION_VIEW);
                install.setDataAndType(fileUri, "application/vnd.android.package-archive");
                install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                // Android 8+: may need to allow unknown sources
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    boolean canInstall = context.getPackageManager().canRequestPackageInstalls();
                    if (!canInstall) {
                        Intent settings = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + context.getPackageName()));
                        settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(settings);
                        Toast.makeText(context, "Allow installs from this app, then open the downloaded APK in Downloads.", Toast.LENGTH_LONG).show();
                        return;
                    }
                }
                context.startActivity(install);
            } else {
                Toast.makeText(context, "Download complete. Open the APK from Downloads.", Toast.LENGTH_LONG).show();
            }
        } catch (Exception ignored) {}
    }
}

