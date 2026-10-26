package fr.pes1335.sentinel;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

/** Alarme plein écran : écran rouge clignotant, sonnerie en boucle, vibration. */
public class AlarmActivity extends Activity {

    static final int RED = Color.rgb(255, 45, 61);
    static final int DARK_RED = Color.rgb(60, 6, 12);
    static final int BLUE = Color.rgb(61, 139, 255);

    private MediaPlayer player;
    private Vibrator vibrator;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView web;
    private String slots = "[]", tasks = "[]";

    private final Runnable silence = this::stopSound;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.rgb(18, 4, 16));
        getWindow().setNavigationBarColor(Color.rgb(18, 4, 16));
        build(getIntent());
        startSound();
        handler.postDelayed(silence, 3 * 60 * 1000L); // coupe la sonnerie au bout de 3 min
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        build(intent);
        startSound();
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void build(Intent in) {
        slots = in.getStringExtra("slots") == null ? "[]" : in.getStringExtra("slots");
        tasks = in.getStringExtra("tasks") == null ? "[]" : in.getStringExtra("tasks");
        final String data;
        String d;
        try {
            JSONObject o = new JSONObject();
            o.put("kind", in.getStringExtra("kind"));
            o.put("title", in.getStringExtra("title"));
            o.put("when", in.getStringExtra("when"));
            o.put("doneLabel", in.getStringExtra("doneLabel"));
            String items = in.getStringExtra("items");
            o.put("items", new JSONArray(items == null ? "[]" : items));
            d = o.toString();
        } catch (Exception e) {
            d = "{}";
        }
        data = d;
        if (web == null) {
            web = new WebView(this);
            web.setBackgroundColor(Color.rgb(18, 4, 16));
            web.getSettings().setJavaScriptEnabled(true);
            web.addJavascriptInterface(new AlarmBridge(), "AlarmBridge");
            setContentView(web);
        }
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                v.evaluateJavascript("show(" + data + ")", null);
            }
        });
        web.loadUrl("file:///android_asset/alarm.html");
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).cancel(AlarmReceiver.NOTIF_ID);
    }

    /** Boutons de l'écran d'alarme. */
    public final class AlarmBridge {
        @JavascriptInterface public void done() { runOnUiThread(() -> finishWith("done", 0)); }
        @JavascriptInterface public void snooze(final int m) { runOnUiThread(() -> finishWith("snooze", m)); }
        @JavascriptInterface public void close() { runOnUiThread(() -> finishWith("close", 0)); }
    }

    /** Sonnerie choisie dans Sentinel, sinon l'alarme par défaut du téléphone. */
    static Uri soundUri(Context c) {
        String s = Api.prefs(c).getString("sound", "");
        if (s != null && s.length() > 0) return Uri.parse(s);
        Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        return u;
    }

    private void startSound() {
        stopSound();
        try {
            Uri u = soundUri(this);
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            try {
                player.setDataSource(this, u);
            } catch (Exception bad) { // sonnerie supprimée ou illisible : on revient à l'alarme par défaut
                player.reset();
                player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
                player.setDataSource(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM));
            }
            player.setLooping(true);
            player.prepare();
            player.start();
        } catch (Exception ignored) {}
        try {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null) vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 700, 300, 700, 300, 700, 1200}, 0));
        } catch (Exception ignored) {}
    }

    private void stopSound() {
        try { if (player != null) { player.stop(); player.release(); } } catch (Exception ignored) {}
        player = null;
        try { if (vibrator != null) vibrator.cancel(); } catch (Exception ignored) {}
    }

    private void finishWith(final String what, final int minutes) {
        stopSound();
        handler.removeCallbacksAndMessages(null);
        final Context c = getApplicationContext();
        final String sl = slots, tk = tasks;
        new Thread(() -> {
            long now = System.currentTimeMillis();
            try {
                JSONArray s = new JSONArray(sl);
                JSONArray t = new JSONArray(tk);
                if ("done".equals(what)) {
                    for (int i = 0; i < s.length(); i++) {
                        JSONObject o = s.getJSONObject(i);
                        try { Api.post(c, "/done", new JSONObject().put("date", o.getString("date")).put("key", o.getString("key")).put("done", true)); } catch (Exception ignored) {}
                    }
                    if (s.length() > 0) Api.prefs(c).edit().putLong("snooze", 0).apply();
                    for (int i = 0; i < t.length(); i++) {
                        JSONObject o = t.getJSONObject(i);
                        String rep = o.optString("repeat", "none");
                        long due = o.optLong("due", 0);
                        if (!"none".equals(rep) && due > 0) {
                            o.put("due", Scheduler.nextDue(due, rep));
                            o.put("firedAt", JSONObject.NULL);
                            o.put("snooze", JSONObject.NULL);
                        } else {
                            o.put("done", true);
                            o.put("doneAt", now);
                        }
                        try { Api.post(c, "/tasks", o); } catch (Exception ignored) {}
                    }
                } else if ("snooze".equals(what)) {
                    long until = now + minutes * 60000L;
                    if (s.length() > 0) Api.prefs(c).edit().putLong("snooze", until).apply();
                    for (int i = 0; i < t.length(); i++) {
                        Api.prefs(c).edit().putLong("tsnooze:" + t.getJSONObject(i).optString("id"), until).apply();
                    }
                }
            } catch (Exception ignored) {}
            try { Scheduler.reschedule(c); } catch (Exception ignored) {}
        }).start();
        finish();
    }

    @Override
    public void onBackPressed() {
        finishWith("close", 0);
    }

    @Override
    protected void onDestroy() {
        stopSound();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

}
