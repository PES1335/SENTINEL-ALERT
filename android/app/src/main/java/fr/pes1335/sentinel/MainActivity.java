package fr.pes1335.sentinel;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

/**
 * Affiche l'interface Sentinel (le site GitHub Pages) et lui donne accès
 * au moteur d'alarmes plein écran de l'appli via l'objet JavaScript « SentinelNative ».
 */
public class MainActivity extends Activity {

    static final String APP_URL = "https://pes1335.github.io/SENTINEL-ALERT/";
    static final int FILE_REQ = 42;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.rgb(4, 7, 16));
        getWindow().setNavigationBarColor(Color.rgb(4, 7, 16));
        AlarmReceiver.channel(this);

        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(7, 11, 20));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        web.addJavascriptInterface(new Bridge(), "SentinelNative");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                String scheme = u.getScheme() == null ? "" : u.getScheme();
                if (scheme.equals("tel") || scheme.equals("mailto") || scheme.equals("sms")) {
                    try { startActivity(new Intent(scheme.equals("tel") ? Intent.ACTION_DIAL : Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                    return true;
                }
                if ("pes1335.github.io".equalsIgnoreCase(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), FILE_REQ);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        setContentView(web);
        web.loadUrl(APP_URL);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_REQ && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCallback = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        final Context c = getApplicationContext();
        new Thread(() -> { try { Scheduler.reschedule(c); } catch (Exception ignored) {} }).start();
        web.evaluateJavascript("document.dispatchEvent(new Event('visibilitychange'))", null);
    }

    @Override
    protected void onPause() {
        web.onPause();
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        web.evaluateJavascript("document.dispatchEvent(new Event('visibilitychange'))", null);
    }

    private void open(String action) {
        try {
            startActivity(new Intent(action, Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
        }
    }

    /** Pont entre l'interface web et les alarmes Android. */
    private final class Bridge {

        @JavascriptInterface
        public String getConfig() {
            try {
                return new JSONObject().put("url", Api.url(MainActivity.this)).put("key", Api.key(MainActivity.this)).toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void setConfig(String url, String key) {
            if (url == null || key == null || key.length() < 8) return;
            Api.prefs(MainActivity.this).edit().putString("url", url).putString("key", key).apply();
            reschedule();
        }

        @JavascriptInterface
        public void reschedule() {
            final Context c = getApplicationContext();
            new Thread(() -> { try { Scheduler.reschedule(c); } catch (Exception ignored) {} }).start();
        }

        @JavascriptInterface
        public void test() {
            Api.prefs(MainActivity.this).edit().putBoolean("test", true).putLong("testAt", System.currentTimeMillis() + 10000L).apply();
            reschedule();
        }

        @JavascriptInterface
        public String status() {
            try {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                boolean notif = (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
                        && nm.areNotificationsEnabled();
                boolean fsi = Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent();
                boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
                boolean battery = pm.isIgnoringBatteryOptimizations(getPackageName());
                long next = Api.prefs(MainActivity.this).getLong("next", 0);
                String label = Api.prefs(MainActivity.this).getString("nextLabel", "");
                String nextText = next > 0
                        ? (Scheduler.dateStr(next).equals(Scheduler.dateStr(System.currentTimeMillis())) ? "" : "demain ") + Scheduler.hhmm(next) + " · " + label
                        : "";
                return new JSONObject().put("notif", notif).put("fsi", fsi).put("exact", exact).put("battery", battery)
                        .put("next", next).put("nextText", nextText).toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void fix(final String what) {
            runOnUiThread(() -> {
                if ("notif".equals(what)) {
                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
                    } else {
                        startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
                    }
                } else if ("fsi".equals(what)) {
                    open("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT");
                } else if ("exact".equals(what)) {
                    open(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                } else if ("battery".equals(what)) {
                    open(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                }
            });
        }
    }
}
