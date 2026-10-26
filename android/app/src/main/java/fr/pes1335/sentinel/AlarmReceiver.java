package fr.pes1335.sentinel;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

/** Réveil : vérifie ce qui doit sonner et ouvre l'alarme plein écran. */
public class AlarmReceiver extends BroadcastReceiver {

    public static final String CHANNEL = "sentinel_alarme";
    public static final int NOTIF_ID = 1001;

    @Override
    public void onReceive(Context context, Intent intent) {
        final Context c = context.getApplicationContext();
        final String action = intent == null ? null : intent.getAction();
        final PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                if (Scheduler.ACTION_FIRE.equals(action)) fire(c);
                Scheduler.reschedule(c);
            } catch (Exception ignored) {
            } finally {
                pr.finish();
            }
        }).start();
    }

    private static void fire(Context c) {
        SharedPreferences p = Api.prefs(c);
        long now = System.currentTimeMillis();
        if (p.getBoolean("test", false) && p.getLong("testAt", 0) <= now + 60000L) {
            p.edit().putBoolean("test", false).apply();
            show(c, "Test", "Alarme Sentinel", "L'alarme plein écran fonctionne.\nAppuie sur « C'est fait » pour l'arrêter.", "[]", "[]", "");
            return;
        }
        Scheduler.Ring r = Scheduler.due(c);
        if (r.isEmpty()) return;

        SharedPreferences.Editor e = p.edit();
        StringBuilder body = new StringBuilder();
        JSONArray slots = new JSONArray();
        JSONArray tasks = new JSONArray();
        String title;
        String kind;
        if (!r.slots.isEmpty()) {
            e.putLong("snooze", now + r.repeat * 60000L); // relance automatique si on ignore
            for (JSONObject row : r.slots) {
                String who = row.optString("agent", "");
                if (who.length() == 0) who = "Poste " + row.optString("placeholder", "");
                String tel = row.optString("tel", "");
                if (tel.length() == 0) tel = row.optString("telSite", "");
                body.append(row.optString("start")).append("  ")
                        .append(row.optString("site").replace("New Yorker ", "NY ")).append("\n   ")
                        .append(who).append(tel.length() > 0 ? "  ·  " + fmtTel(tel) : "").append("\n");
                try { slots.put(new JSONObject().put("date", r.date).put("key", row.optString("key"))); } catch (Exception ignored) {}
            }
            int n = r.slots.size();
            kind = "Débuts de service";
            title = n + " agent" + (n > 1 ? "s" : "") + " à appeler";
        } else {
            kind = "Objectif";
            title = r.tasks.get(0).optString("title");
        }
        for (int i = 0; i < r.tasks.size(); i++) {
            JSONObject t = r.tasks.get(i);
            e.putBoolean("rung:" + t.optString("id") + ":" + r.taskAt.get(i), true);
            e.remove("tsnooze:" + t.optString("id"));
            tasks.put(t);
            if (!r.slots.isEmpty() || i > 0) body.append("Objectif : ").append(t.optString("title")).append("\n");
            else {
                long due = t.optLong("due", 0);
                if (due > 0) body.append("Échéance ").append(Scheduler.hhmm(due)).append("\n");
                int prio = t.optInt("prio", 0);
                if (prio == 2) body.append("Priorité urgente\n");
                else if (prio == 1) body.append("Priorité importante\n");
            }
        }
        e.apply();
        show(c, kind, title, body.toString().trim(), slots.toString(), tasks.toString(), r.date);
    }

    static String fmtTel(String t) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            if (i > 0 && i % 2 == 0) s.append(' ');
            s.append(t.charAt(i));
        }
        return s.toString();
    }

    static void channel(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Alarmes Sentinel", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Alarmes plein écran des débuts de service et des objectifs");
        Uri snd = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        ch.setSound(snd, new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[]{0, 600, 200, 600, 200, 600});
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        ch.setBypassDnd(true);
        nm.createNotificationChannel(ch);
    }

    static void show(Context c, String kind, String title, String body, String slots, String tasks, String date) {
        channel(c);
        Intent i = new Intent(c, AlarmActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                .putExtra("kind", kind).putExtra("title", title).putExtra("body", body)
                .putExtra("slots", slots).putExtra("tasks", tasks).putExtra("date", date);
        PendingIntent pi = PendingIntent.getActivity(c, (int) (System.currentTimeMillis() % 100000), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(kind + " · " + title)
                .setContentText(body.replace("\n", "  "))
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setFullScreenIntent(pi, true)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        try { nm.notify(NOTIF_ID, n); } catch (Exception ignored) {}
        try { c.startActivity(i); } catch (Exception ignored) {}
    }
}
