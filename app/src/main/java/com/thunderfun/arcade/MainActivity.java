package com.thunderfun.arcade;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Thunderfun Arcade: a small launcher for the games Claude makes.
 *
 * The screen is a page bundled inside the app (assets/hub.html). It never loads anything from the web itself:
 * the app downloads the public game list (games.json in the GitHub repo) and hands it to the page, and when a
 * tile is tapped the app opens that game in Firefox, where you are signed in to Claude.
 *
 * Safety rules:
 *  - The only permission is INTERNET (to read the game list).
 *  - The page cannot navigate anywhere; every link is handed to openLink(), which only allows https://claude.ai
 *    games and this repo's Releases page. Anything else is refused.
 *  - The app never sees or stores any Claude login: that lives in Firefox.
 */
public class MainActivity extends Activity {

    private static final String REPO = "antthunderfun/thunderfun-arcade";
    private static final String LIST_API = "https://api.github.com/repos/" + REPO + "/contents/games.json?ref=main";
    private static final String LIST_RAW = "https://raw.githubusercontent.com/" + REPO + "/main/games.json";
    private static final String RELEASES = "https://github.com/" + REPO + "/releases";
    private static final String FIREFOX = "org.mozilla.firefox";
    private static final int MAX_LIST_BYTES = 512 * 1024;

    private WebView web;
    private SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private long lastRefresh = 0;
    private boolean pageReady = false;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("arcade", MODE_PRIVATE);

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#120f1c"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(false);
        s.setAllowFileAccess(false);          // bundled assets still load; the phone's files do not
        s.setAllowContentAccess(false);
        s.setGeolocationEnabled(false);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) s.setForceDark(WebSettings.FORCE_DARK_OFF);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // never navigate the hub away from itself; send real links through the same checks as a tap
                Uri u = request.getUrl();
                if (u != null && !"file".equals(u.getScheme())) openLink(u.toString());
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        web.loadUrl("file:///android_asset/hub.html");
    }

    @Override
    protected void onResume() {
        super.onResume();
        // coming back from a game: check for new games, at most once a minute
        if (pageReady && System.currentTimeMillis() - lastRefresh > 60_000) {
            web.evaluateJavascript("window.onResumeApp && window.onResumeApp()", null);
        }
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }

    /** The only links the arcade will ever open. */
    static boolean allowed(String url) {
        if (url == null) return false;
        Uri u = Uri.parse(url);
        if (!"https".equals(u.getScheme()) || u.getUserInfo() != null || u.getPort() != -1) return false;
        String host = u.getHost(), path = u.getPath() == null ? "" : u.getPath();
        if ("claude.ai".equals(host)) return path.startsWith("/artifact/") || path.startsWith("/code/artifact/") || path.startsWith("/public/artifacts/");
        if ("github.com".equals(host)) return path.equals("/" + REPO + "/releases") || path.startsWith("/" + REPO + "/releases/");
        return false;
    }

    private boolean openLink(String url) {
        if (!allowed(url)) return false;
        Uri uri = Uri.parse(url);
        Intent ff = new Intent(Intent.ACTION_VIEW, uri).setPackage(FIREFOX).addCategory(Intent.CATEGORY_BROWSABLE);
        try {
            startActivity(ff);
            return true;
        } catch (ActivityNotFoundException e) {
            // Firefox isn't installed: fall back to the phone's default browser
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE));
                return true;
            } catch (ActivityNotFoundException e2) {
                return false;
            }
        }
    }

    private static String download(String address, boolean api) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(15_000);
        c.setInstanceFollowRedirects(true);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", "ThunderfunArcade-Android");
        c.setRequestProperty("Cache-Control", "no-cache");
        if (api) {
            c.setRequestProperty("Accept", "application/vnd.github.raw+json");
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        }
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n, total = 0;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > MAX_LIST_BYTES) throw new Exception("list too large");
                    out.write(buf, 0, n);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }

    private void callPage(String js) {
        main.post(() -> { if (web != null) web.evaluateJavascript(js, null); });
    }

    /** What the hub page is allowed to ask the app for. */
    private class Bridge {
        @JavascriptInterface
        public String cachedList() {
            return prefs.getString("list", "");
        }

        @JavascriptInterface
        public void refresh() {
            lastRefresh = System.currentTimeMillis();
            new Thread(() -> {
                String body = null;
                Exception err = null;
                // the API answers with the newest list straight away; the raw file is the backup
                for (int i = 0; i < 2 && body == null; i++) {
                    try {
                        String b = i == 0 ? download(LIST_API, true) : download(LIST_RAW + "?t=" + System.currentTimeMillis(), false);
                        new JSONObject(b);   // only keep it if it is a valid list
                        body = b;
                    } catch (Exception e) {
                        err = e;
                    }
                }
                if (body != null) {
                    prefs.edit().putString("list", body).apply();
                    callPage("window.onList(" + JSONObject.quote(body) + ", true)");
                } else {
                    callPage("window.onListError(" + JSONObject.quote(String.valueOf(err)) + ")");
                }
            }).start();
        }

        @JavascriptInterface
        public boolean open(String url) {
            if (!allowed(url)) return false;
            main.post(() -> openLink(url));
            return true;
        }

        @JavascriptInterface
        public String getStats() {
            return prefs.getString("stats", "");
        }

        @JavascriptInterface
        public void setStats(String json) {
            if (json != null && json.length() < 200_000) prefs.edit().putString("stats", json).apply();
        }

        @JavascriptInterface
        public int appVersion() {
            try {
                PackageInfo p = getPackageManager().getPackageInfo(getPackageName(), 0);
                return (int) (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? p.getLongVersionCode() : p.versionCode);
            } catch (Exception e) {
                return 0;
            }
        }
    }
}
