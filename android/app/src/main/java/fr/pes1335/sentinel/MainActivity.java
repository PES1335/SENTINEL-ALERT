package fr.pes1335.sentinel;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/** Écran principal : liaison au serveur, autorisations, test, liste du jour. */
public class MainActivity extends Activity {

    static final int BG = Color.rgb(7, 11, 20);
    static final int SURFACE = Color.rgb(14, 20, 36);
    static final int INK = Color.rgb(223, 230, 245);
    static final int MUTED = Color.rgb(120, 132, 163);
    static final int LINE = Color.rgb(31, 42, 68);
    static final int BLUE = Color.rgb(61, 139, 255);
    static final int RED = Color.rgb(255, 45, 61);
    static final int GREEN = Color.rgb(63, 207, 149);

    private EditText urlField, keyField;
    private TextView nextView, listView;
    private LinearLayout permBox;

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif-condensed", bold ? Typeface.BOLD : Typeface.NORMAL));
        return t;
    }

    private TextView label(String s) {
        TextView t = text(s.toUpperCase(), 12, BLUE, false);
        t.setTypeface(Typeface.MONOSPACE);
        t.setLetterSpacing(0.15f);
        t.setPadding(0, dp(22), 0, dp(8));
        return t;
    }

    private Button button(String s, int bg, boolean outline) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(true);
        b.setTextColor(outline ? INK : Color.WHITE);
        b.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        GradientDrawable g = new GradientDrawable();
        g.setColor(outline ? Color.TRANSPARENT : bg);
        g.setStroke(dp(1), outline ? LINE : bg);
        b.setBackground(g);
        b.setPadding(dp(12), dp(12), dp(12), dp(12));
        return b;
    }

    private EditText field(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        e.setTextColor(INK);
        e.setHintTextColor(MUTED);
        e.setTypeface(Typeface.MONOSPACE);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        GradientDrawable g = new GradientDrawable();
        g.setColor(SURFACE);
        g.setStroke(dp(1), LINE);
        e.setBackground(g);
        e.setPadding(dp(12), dp(12), dp(12), dp(12));
        return e;
    }

    private LinearLayout.LayoutParams gap(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(top);
        return lp;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.rgb(4, 7, 16));
        getWindow().setNavigationBarColor(Color.rgb(4, 7, 16));

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(28), dp(18), dp(40));
        sv.addView(root);

        TextView title = text("SENTINEL", 30, INK, true);
        title.setLetterSpacing(0.08f);
        root.addView(title);
        TextView sub = text("ALARMES PLEIN ÉCRAN", 12, BLUE, false);
        sub.setTypeface(Typeface.MONOSPACE);
        sub.setLetterSpacing(0.2f);
        root.addView(sub);

        root.addView(label("Prochaine alarme"));
        nextView = text("…", 20, INK, true);
        root.addView(nextView);

        root.addView(label("1 · Autorisations"));
        permBox = new LinearLayout(this);
        permBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(permBox);

        root.addView(label("2 · Serveur"));
        SharedPreferencesHolder p = new SharedPreferencesHolder(this);
        urlField = field("Adresse du serveur", p.url);
        root.addView(urlField);
        keyField = field("Code d'accès (Réglages de Sentinel Alertes)", p.key);
        root.addView(keyField, gap(8));
        Button save = button("Relier", BLUE, false);
        save.setOnClickListener(v -> saveServer());
        root.addView(save, gap(10));

        root.addView(label("3 · Vérifier"));
        Button test = button("Tester l'alarme (dans 10 secondes)", RED, false);
        test.setOnClickListener(v -> {
            Api.prefs(this).edit().putBoolean("test", true).putLong("testAt", System.currentTimeMillis() + 10000L).apply();
            refresh();
            Toast.makeText(this, "Verrouille ton téléphone : l'alarme arrive dans 10 s", Toast.LENGTH_LONG).show();
        });
        root.addView(test);

        root.addView(label("Débuts de service aujourd'hui"));
        listView = text("…", 15, INK, false);
        listView.setTypeface(Typeface.MONOSPACE);
        listView.setLineSpacing(dp(2), 1f);
        root.addView(listView);
        Button reload = button("Actualiser", 0, true);
        reload.setOnClickListener(v -> refresh());
        root.addView(reload, gap(12));

        TextView foot = text("Les confirmations sont partagées avec Sentinel Alertes. Coche un agent ici ou là-bas : l'alarme s'arrête partout.", 13, MUTED, false);
        foot.setPadding(0, dp(18), 0, 0);
        root.addView(foot);

        setContentView(sv);
        AlarmReceiver.channel(this);
    }

    /** Petit utilitaire pour lire les réglages enregistrés. */
    private static final class SharedPreferencesHolder {
        final String url, key;
        SharedPreferencesHolder(Context c) {
            url = Api.url(c);
            key = Api.key(c);
        }
    }

    private void saveServer() {
        String u = urlField.getText().toString().trim();
        String k = keyField.getText().toString().trim();
        if (!u.startsWith("https://")) { Toast.makeText(this, "L'adresse doit commencer par https://", Toast.LENGTH_LONG).show(); return; }
        if (k.length() < 8) { Toast.makeText(this, "Colle le code d'accès affiché dans Sentinel Alertes", Toast.LENGTH_LONG).show(); return; }
        Api.prefs(this).edit().putString("url", u).putString("key", k).apply();
        new Thread(() -> {
            String msg;
            try { Api.get(this, "/today"); msg = "Serveur relié"; }
            catch (Exception e) { msg = "Échec : " + e.getMessage(); }
            final String m = msg;
            runOnUiThread(() -> { Toast.makeText(this, m, Toast.LENGTH_LONG).show(); refresh(); });
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        drawPermissions();
        refresh();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        drawPermissions();
    }

    private void permRow(String name, boolean ok, View.OnClickListener fix) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(4), 0, dp(4));
        TextView t = text((ok ? "✓  " : "✗  ") + name, 16, ok ? GREEN : RED, true);
        row.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (!ok) {
            Button b = button("Autoriser", BLUE, false);
            b.setOnClickListener(fix);
            row.addView(b);
        }
        permBox.addView(row);
    }

    private void open(String action) {
        try {
            startActivity(new Intent(action, Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
        }
    }

    private void drawPermissions() {
        permBox.removeAllViews();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);

        boolean notif = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        permRow("Notifications", notif && nm.areNotificationsEnabled(), v -> {
            if (Build.VERSION.SDK_INT >= 33 && !notif) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            else startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        });
        boolean fsi = Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent();
        permRow("Alarme plein écran", fsi, v -> open("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT"));
        boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
        permRow("Alarmes à l'heure exacte", exact, v -> open(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM));
        boolean battery = pm.isIgnoringBatteryOptimizations(getPackageName());
        permRow("Batterie sans restriction", battery, v -> open(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS));
    }

    private void refresh() {
        new Thread(() -> {
            try { Scheduler.reschedule(this); } catch (Exception ignored) {}
            String list;
            if (!Api.configured(this)) list = "Relie d'abord le serveur (étape 2).";
            else {
                JSONObject d = Scheduler.day(this, Scheduler.dateStr(System.currentTimeMillis()));
                StringBuilder s = new StringBuilder();
                JSONArray main = d == null ? null : d.optJSONArray("main");
                if (main == null) list = "Serveur injoignable. Vérifie l'adresse et le code.";
                else if (main.length() == 0) list = "Aucun début de service aujourd'hui.\nImporte ton planning PDF dans Sentinel Alertes.";
                else {
                    for (int i = 0; i < main.length(); i++) {
                        JSONObject r = main.optJSONObject(i);
                        if (r == null) continue;
                        String who = r.optString("agent", "");
                        if (who.length() == 0) who = "Poste " + r.optString("placeholder", "");
                        s.append(Scheduler.isDone(r) ? "✓ " : "· ").append(r.optString("start")).append("  ")
                                .append(r.optString("site").replace("New Yorker ", "NY ")).append("\n     ").append(who).append("\n");
                    }
                    list = s.toString().trim();
                }
            }
            long next = Api.prefs(this).getLong("next", 0);
            String label = Api.prefs(this).getString("nextLabel", "");
            final String nextText = next > 0
                    ? (Scheduler.dateStr(next).equals(Scheduler.dateStr(System.currentTimeMillis())) ? "" : "Demain ") + Scheduler.hhmm(next) + "  ·  " + label
                    : "Aucune alarme prévue";
            final String l = list;
            runOnUiThread(() -> { nextView.setText(nextText); listView.setText(l); });
        }).start();
    }
}
