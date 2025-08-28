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

    private boolean offlineShown = false;
    private ConnectivityManager.NetworkCallback networkCallback = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String[] perms = new String[] {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        };
        ActivityCompat.requestPermissions(this, perms, RUNTIME_PERMS_REQ);

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

        // Auto-reload when connectivity returns if offline page is showing
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm != null) {
                networkCallback = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        runOnUiThread(() -> {
                            String cur = webView.getUrl();
                            if (offlineShown || (cur != null && cur.startsWith("file:"))) {
                                webView.loadUrl(START_URL);
                                offlineShown = false;
                            }
                        });
                    }
                };
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    cm.registerDefaultNetworkCallback(networkCallback);
                } else {
                    NetworkRequest req = new NetworkRequest.Builder()
                            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            .build();
                    cm.registerNetworkCallback(req, networkCallback);
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

        // Check for updates on startup
        checkForUpdatesAsync();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (networkCallback != null) {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                if (cm != null) cm.unregisterNetworkCallback(networkCallback);
            }
        } catch (Throwable ignored) { }
    }

    private void checkForUpdatesAsync() {
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
                    if (isNewer(latest, currentVersion)) {
                        runOnUiThread(() -> promptUpdate(latest));
                    }
                }
                conn.disconnect();
            } catch (Exception ignored) { }
        }).start();
    }

    private void promptUpdate(String latest) {
        new AlertDialog.Builder(this)
                .setTitle("Update available")
                .setMessage("A new version (" + latest + ") is available. Open the releases page to download?")
                .setPositiveButton("Open", (d, w) -> {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(GH_RELEASES_PAGE))); } catch (Exception ignored) { }
                })
                .setNegativeButton("Later", null)
                .show();
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
