package com.desmond.fimfiction;

import android.Manifest;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;
import android.widget.CheckBox;
import android.content.SharedPreferences;

import androidx.activity.OnBackPressedCallback;
import androidx.core.app.ActivityCompat;

import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebChromeClient;
import com.getcapacitor.BridgeWebViewClient;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends BridgeActivity {

    private static final int RUNTIME_PERMS_REQ = 1000;
    private static final String START_URL = "https://www.fimfiction.net";
    private static final String OFFLINE_URL = "file:///android_asset/public/offline.html";
    private static final String GH_API_LATEST = "https://api.github.com/repos/DarrylClay2005/fimfiction-capacitor/releases/latest";
    private static final String GH_RELEASES_PAGE = "https://github.com/DarrylClay2005/fimfiction-capacitor/releases/latest";

    private static final String EXTRA_WHATS_NEW_VERSION = "whats_new_version";
    private static final String EXTRA_WHATS_NEW_BODY = "whats_new_body";

    private boolean offlineShown = false;
    private ConnectivityManager.NetworkCallback networkCallback = null;

    private static final int MENU_ID_RELOAD = 1001;
    private static final int MENU_ID_CHECK_UPDATES = 1002;

    private long lastReloadMs = 0;
    private int reloadAttempts = 0;
    private static final int RELOAD_WINDOW_MS = 30_000; // 30 seconds
    private static final int RELOAD_MAX_ATTEMPTS = 3;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String[] perms = new String[] {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            (Build.VERSION.SDK_INT >= 33 ? Manifest.permission.POST_NOTIFICATIONS : null)
        };
        // Filter nulls (POST_NOTIFICATIONS on older versions)
        java.util.ArrayList<String> req = new java.util.ArrayList<>();
        for (String p : perms) if (p != null) req.add(p);
        ActivityCompat.requestPermissions(this, req.toArray(new String[0]), RUNTIME_PERMS_REQ);

        final WebView webView = getBridge().getWebView();
        // Enable WebView debugging for debug builds (detect debuggable flag at runtime)
        try { boolean debug = (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0; WebView.setWebContentsDebuggingEnabled(debug); } catch (Throwable t) { /* ignore */ }

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportMultipleWindows(true);

        webView.setWebViewClient(new BridgeWebViewClient(getBridge()) {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase() : "";
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    String host = uri.getHost() != null ? uri.getHost().toLowerCase() : "";
                    // Keep only fimfiction.net and subdomains in-app; open others externally
                    if (host.endsWith("fimfiction.net")) {
                        return false; // in-app
                    } else {
                        try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) { }
                        return true; // handled externally
                    }
                }
                // For non-http(s) schemes (e.g., mailto:, tel:), hand off to the system
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                    startActivity(intent);
                } catch (Exception ignored) { }
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                if (url != null && !url.startsWith("file:")) {
                    offlineShown = false;
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && request.isForMainFrame()) {
                    offlineShown = true;
                    view.loadUrl(OFFLINE_URL);
                }
            }

            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                    offlineShown = true;
                    view.loadUrl(OFFLINE_URL);
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                if (request.isForMainFrame() && errorResponse != null && errorResponse.getStatusCode() >= 400) {
                    offlineShown = true;
                    view.loadUrl(OFFLINE_URL);
                }
            }
        });

        webView.setWebChromeClient(new AppWebChromeClient(getBridge()));

        // Auto-reload when connectivity returns if offline page is showing (throttled)
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm != null) {
                networkCallback = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        runOnUiThread(() -> {
                            String cur = webView.getUrl();
                            if (offlineShown || (cur != null && cur.startsWith("file:"))) {
                                attemptReload(false);
                            }
                        });
                    }
                };
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    cm.registerDefaultNetworkCallback(networkCallback);
                } else {
                    NetworkRequest netReq = new NetworkRequest.Builder()
                            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            .build();
                    cm.registerNetworkCallback(netReq, networkCallback);
                }
            }
        } catch (Throwable ignored) { }

        // Handle file downloads via Android DownloadManager
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimetype, long contentLength) {
                try {
                    DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                    if (dm == null) return;
                    String fileName = URLUtil.guessFileName(url, contentDisposition, mimetype);
                    DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                    req.addRequestHeader("User-Agent", userAgent);
                    req.setMimeType(mimetype);
                    req.setTitle(fileName);
                    req.setDescription("Downloading...");
                    req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
                    dm.enqueue(req);
                } catch (Exception ignored) { }
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    setEnabled(false);
                    MainActivity.super.onBackPressed();
                }
            }
        });

        // Check for updates on startup (silent)
        checkForUpdatesAsync(false);
        // Check intent for What's New (cold start)
        handleIncomingIntent(getIntent());
        // Schedule periodic background checks via WorkManager
        schedulePeriodicUpdateChecks();
    }

    @Override
    public void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    public void onResume() {
        super.onResume();
        // If returning from background and offline page is showing, try a throttled reload
        try {
            final WebView webView = getBridge().getWebView();
            String cur = webView.getUrl();
            if (offlineShown || (cur != null && cur.startsWith("file:"))) {
                attemptReload(false);
            }
        } catch (Throwable ignored) {}
    }

    public void onDestroy() {
        super.onDestroy();
        try {
            if (networkCallback != null) {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                if (cm != null) cm.unregisterNetworkCallback(networkCallback);
            }
        } catch (Throwable ignored) { }
    }

    private void checkForUpdatesAsync(boolean interactive) {
        new Thread(() -> {
            try {
                String currentVersion = "0.0.0";
                long currentCode = 0;
                try {
                    PackageManager pm = getPackageManager();
                    PackageInfo pi = (Build.VERSION.SDK_INT >= 33)
                            ? pm.getPackageInfo(getPackageName(), PackageManager.PackageInfoFlags.of(0))
                            : pm.getPackageInfo(getPackageName(), 0);
                    currentVersion = pi.versionName != null ? pi.versionName : currentVersion;
                    currentCode = (Build.VERSION.SDK_INT >= 28) ? pi.getLongVersionCode() : pi.versionCode;
                } catch (Exception ignored) {}

                HttpURLConnection conn = (HttpURLConnection) new URL(GH_API_LATEST).openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setRequestProperty("User-Agent", getPackageName());
                int code = conn.getResponseCode();
                if (code == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line; while ((line = br.readLine()) != null) sb.append(line);
                    br.close();
                    JSONObject obj = new JSONObject(sb.toString());
                    String tag = obj.optString("tag_name", "");
                    String latest = tag.startsWith("v") ? tag.substring(1) : tag;
                    String apkUrl = null;
                    try {
                        var assets = obj.optJSONArray("assets");
                        if (assets != null) {
                            for (int i = 0; i < assets.length(); i++) {
                                var a = assets.optJSONObject(i);
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
                    } catch (Throwable ignored2) {}
                    if (isNewer(latest, currentVersion)) {
                        final String fApk = apkUrl;
                        runOnUiThread(() -> promptUpdate(latest, fApk));
                    } else if (interactive) {
                        runOnUiThread(() -> Toast.makeText(this, "App is up to date", Toast.LENGTH_SHORT).show());
                    }
                } else if (interactive) {
                    runOnUiThread(() -> Toast.makeText(this, "Update check failed (" + code + ")", Toast.LENGTH_SHORT).show());
                }
                conn.disconnect();
            } catch (Exception ignored) { }
        }).start();
    }

    private void promptUpdate(String latest, String apkUrl) {
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Update available")
                .setMessage("A new version (" + latest + ") is available.");
        if (apkUrl != null && !apkUrl.isEmpty()) {
            b.setPositiveButton("Download", (d, w) -> startApkDownload(apkUrl));
            b.setNeutralButton("Releases", (d, w) -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(GH_RELEASES_PAGE))); } catch (Exception ignored) { }
            });
            b.setNegativeButton("Later", null);
        } else {
            b.setPositiveButton("Open Releases", (d, w) -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(GH_RELEASES_PAGE))); } catch (Exception ignored) { }
            });
            b.setNegativeButton("Later", null);
        }
        b.show();
    }

    private void startApkDownload(String url) {
        try {
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (dm == null) return;
            String fileName = URLUtil.guessFileName(url, null, "application/vnd.android.package-archive");
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setMimeType("application/vnd.android.package-archive");
            req.setTitle(fileName);
            req.setDescription("Downloading update...");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            dm.enqueue(req);
            Toast.makeText(this, "Downloading update...", Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) { }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_ID_RELOAD, 0, "Reload");
        menu.add(0, MENU_ID_CHECK_UPDATES, 1, "Check for updates");
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == MENU_ID_RELOAD) {
            attemptReload(true);
            return true;
        } else if (item.getItemId() == MENU_ID_CHECK_UPDATES) {
            checkForUpdatesAsync(true);
            return true;
        }
        return super.onOptionsItemSelected(item);
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

    private boolean isOnline() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network n = cm.getActiveNetwork();
                if (n == null) return false;
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            } else {
                android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
                return ni != null && ni.isConnected();
            }
        } catch (Throwable t) { return false; }
    }

    private void schedulePeriodicUpdateChecks() {
        try {
            UpdateCheckWorker.schedule(this);
        } catch (Throwable ignored) { }
    }

    private void handleIncomingIntent(android.content.Intent intent) {
        if (intent == null) return;
        String v = intent.getStringExtra(EXTRA_WHATS_NEW_VERSION);
        String b = intent.getStringExtra(EXTRA_WHATS_NEW_BODY);
        if (v != null) {
            if (!hasSeenWhatsNew(v)) {
                showWhatsNewDialog(v, b);
            }
            // prevent showing repeatedly on next resumes
            intent.removeExtra(EXTRA_WHATS_NEW_VERSION);
            intent.removeExtra(EXTRA_WHATS_NEW_BODY);
        }
    }

    private boolean hasSeenWhatsNew(String version) {
        try {
            SharedPreferences p = getSharedPreferences("whats_new", MODE_PRIVATE);
            return p.getBoolean("seen_v_" + version, false);
        } catch (Throwable t) { return false; }
    }

    private void markWhatsNewSeen(String version) {
        try {
            SharedPreferences p = getSharedPreferences("whats_new", MODE_PRIVATE);
            p.edit().putBoolean("seen_v_" + version, true).apply();
        } catch (Throwable ignored) {}
    }

    private void showWhatsNewDialog(String version, String body) {
        String msg = (body != null && !body.isEmpty()) ? body : ("New version available: " + version);
        CheckBox cb = new CheckBox(this);
        cb.setText("Don't show again for this version");
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("What's New: " + version)
                .setMessage(msg)
                .setView(cb)
                .setPositiveButton("OK", (d, w) -> {
                    if (cb.isChecked()) markWhatsNewSeen(version);
                })
                .setNeutralButton("Releases", (d, w) -> {
                    if (cb.isChecked()) markWhatsNewSeen(version);
                    try { startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(GH_RELEASES_PAGE))); } catch (Exception ignored) { }
                })
                .setNegativeButton("Later", null);
        b.show();
    }

    private void attemptReload(boolean forced) {
        final WebView webView = getBridge().getWebView();
        long now = System.currentTimeMillis();
        if (now - lastReloadMs > RELOAD_WINDOW_MS) {
            reloadAttempts = 0;
        }
        if (!forced && reloadAttempts >= RELOAD_MAX_ATTEMPTS) {
            return;
        }
        lastReloadMs = now;
        reloadAttempts++;
        try {
            webView.loadUrl("file:///android_asset/public/reloading.html");
        } catch (Throwable ignored) {}
        webView.postDelayed(() -> {
            if (isOnline()) {
                offlineShown = false;
                webView.loadUrl(START_URL);
            } else {
                offlineShown = true;
                webView.loadUrl(OFFLINE_URL);
            }
        }, 900);
    }

    static class AppWebChromeClient extends BridgeWebChromeClient {
        private final Bridge bridge;

        AppWebChromeClient(Bridge bridge) {
            super(bridge);
            this.bridge = bridge;
        }

        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            bridge.getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    request.grant(request.getResources());
                }
            });
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
            callback.invoke(origin, true, false);
        }

        @Override
        public boolean onCreateWindow(final WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
            WebView newWebView = new WebView(view.getContext());
            newWebView.setWebViewClient(new android.webkit.WebViewClient() {
                @Override
                public void onPageStarted(WebView view2, String url, android.graphics.Bitmap favicon) {
                    view.loadUrl(url);
                }
            });
            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(newWebView);
            resultMsg.sendToTarget();
            return true;
        }
    }
}
