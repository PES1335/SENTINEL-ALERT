package fr.pes1335.sentinel;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

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
    private LinearLayout root;
    private boolean blink;
    private String slots = "[]", tasks = "[]";

    private final Runnable blinker = new Runnable() {
        @Override public void run() {
            blink = !blink;
            root.setBackgroundColor(blink ? Color.rgb(120, 10, 22) : DARK_RED);
            handler.postDelayed(this, 700);
        }
    };
    private final Runnable silence = this::stopSound;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(DARK_RED);
        getWindow().setNavigationBarColor(DARK_RED);
        build(getIntent());
        startSound();
        handler.post(blinker);
        handler.postDelayed(silence, 3 * 60 * 1000L); // coupe la sonnerie au bout de 3 min
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        build(intent);
        startSound();
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private TextView text(String s, int sp, int color, boolean bold, boolean mono) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(mono ? Typeface.MONOSPACE : Typeface.create("sans-serif-condensed", bold ? Typeface.BOLD : Typeface.NORMAL));
        return t;
    }

    private Button button(String s, int bg, int fg, boolean outline) {
        Button btn = new Button(this);
        btn.setText(s);
        btn.setAllCaps(true);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        btn.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        btn.setTextColor(fg);
        GradientDrawable g = new GradientDrawable();
        g.setColor(outline ? Color.TRANSPARENT : bg);
        g.setStroke(dp(2), outline ? Color.argb(160, 255, 255, 255) : bg);
        btn.setBackground(g);
        btn.setPadding(dp(12), dp(14), dp(12), dp(14));
        return btn;
    }

    private void build(Intent in) {
        String kind = in.getStringExtra("kind");
        String title = in.getStringExtra("title");
        String body = in.getStringExtra("body");
        slots = in.getStringExtra("slots") == null ? "[]" : in.getStringExtra("slots");
        tasks = in.getStringExtra("tasks") == null ? "[]" : in.getStringExtra("tasks");

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(DARK_RED);
        root.setPadding(dp(22), dp(36), dp(22), dp(28));

        TextView k = text("⚠  " + (kind == null ? "ALERTE" : kind.toUpperCase()), 15, RED, true, true);
        k.setLetterSpacing(0.15f);
        root.addView(k);

        TextView clock = text(Scheduler.hhmm(System.currentTimeMillis()), 64, Color.WHITE, true, true);
        root.addView(clock);

        TextView t = text(title == null ? "Sentinel" : title, 30, Color.WHITE, true, false);
        t.setAllCaps(true);
        t.setPadding(0, dp(4), 0, dp(14));
        root.addView(t);

        ScrollView sv = new ScrollView(this);
        TextView bodyView = text(body == null ? "" : body, 18, Color.rgb(240, 220, 222), false, true);
        bodyView.setLineSpacing(dp(3), 1f);
        sv.addView(bodyView);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        Button done = button("C'est fait", BLUE, Color.WHITE, false);
        done.setOnClickListener(v -> finishWith("done", 0));
        LinearLayout.LayoutParams full = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        full.topMargin = dp(16);
        root.addView(done, full);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button s5 = button("Rappel 5 min", 0, Color.WHITE, true);
        Button s10 = button("Rappel 10 min", 0, Color.WHITE, true);
        s5.setOnClickListener(v -> finishWith("snooze", 5));
        s10.setOnClickListener(v -> finishWith("snooze", 10));
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        half2.leftMargin = dp(10);
        row.addView(s5, half);
        row.addView(s10, half2);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(10);
        root.addView(row, rowLp);

        TextView close = text("Couper le son (rappel automatique plus tard)", 14, Color.argb(190, 255, 255, 255), false, false);
        close.setGravity(Gravity.CENTER);
        close.setPadding(0, dp(16), 0, dp(4));
        close.setOnClickListener(v -> finishWith("close", 0));
        root.addView(close);

        setContentView(root);
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).cancel(AlarmReceiver.NOTIF_ID);
    }

    private void startSound() {
        stopSound();
        try {
            Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            player.setDataSource(this, u);
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

    @SuppressWarnings("unused")
    private void unused(View v) {}
}
