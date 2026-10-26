package fr.pes1335.sentinel;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Calcule la prochaine alarme (débuts de service + objectifs) et la programme.
 * Toujours appeler depuis un fil d'exécution en arrière-plan (accès réseau).
 */
public final class Scheduler {
    private Scheduler() {}

    public static final String ACTION_FIRE = "fr.pes1335.sentinel.FIRE";
    public static final String ACTION_REFRESH = "fr.pes1335.sentinel.REFRESH";
    private static final long SOON = 3000L;
    private static final long TOLERANCE = 60000L;

    /** Ce qui doit sonner maintenant. */
    public static final class Ring {
        public final String date;
        public final List<JSONObject> slots = new ArrayList<>();
        public final List<JSONObject> tasks = new ArrayList<>();
        public final List<Long> taskAt = new ArrayList<>();
        public int repeat = 10;
        Ring(String date) { this.date = date; }
        public boolean isEmpty() { return slots.isEmpty() && tasks.isEmpty(); }
    }

    /* ---------- Dates ---------- */
    public static String dateStr(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return String.format(Locale.US, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    public static long slotAt(String date, String hhmm, int offsetMin) {
        try {
            String[] d = date.split("-");
            String[] t = hhmm.split(":");
            Calendar c = Calendar.getInstance();
            c.set(Integer.parseInt(d[0]), Integer.parseInt(d[1]) - 1, Integer.parseInt(d[2]), Integer.parseInt(t[0]), Integer.parseInt(t[1]), 0);
            c.set(Calendar.MILLISECOND, 0);
            return c.getTimeInMillis() + offsetMin * 60000L;
        } catch (Exception e) {
            return 0;
        }
    }

    public static String hhmm(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return String.format(Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    /* ---------- Données (avec copie locale si pas de réseau) ---------- */
    public static JSONObject day(Context c, String date) {
        SharedPreferences p = Api.prefs(c);
        try {
            JSONObject d = Api.get(c, "/today?date=" + date);
            p.edit().putString("day:" + date, d.toString()).apply();
            return d;
        } catch (Exception e) {
            try {
                String s = p.getString("day:" + date, null);
                return s == null ? null : new JSONObject(s);
            } catch (Exception e2) {
                return null;
            }
        }
    }

    public static JSONArray tasks(Context c) {
        SharedPreferences p = Api.prefs(c);
        try {
            JSONArray a = Api.get(c, "/tasks").optJSONArray("tasks");
            if (a == null) a = new JSONArray();
            p.edit().putString("tasks", a.toString()).apply();
            return a;
        } catch (Exception e) {
            try {
                return new JSONArray(p.getString("tasks", "[]"));
            } catch (Exception e2) {
                return new JSONArray();
            }
        }
    }

    static boolean isDone(JSONObject row) {
        Object d = row.opt("done");
        if (d == null || d == JSONObject.NULL) return false;
        if (d instanceof Boolean) return (Boolean) d;
        if (d instanceof Number) return ((Number) d).longValue() > 0;
        return true;
    }

    static long taskAlertAt(Context c, JSONObject t) {
        if (t.optBoolean("done", false)) return 0;
        long due = t.optLong("due", 0);
        int remind = t.optInt("remind", -1);
        long local = Api.prefs(c).getLong("tsnooze:" + t.optString("id"), 0);
        if (local > 0) return local;
        if (due <= 0 || remind < 0) return 0;
        return due - remind * 60000L;
    }

    static boolean rung(Context c, JSONObject t, long at) {
        return Api.prefs(c).getBoolean("rung:" + t.optString("id") + ":" + at, false);
    }

    /* ---------- Ce qui sonne maintenant ---------- */
    public static Ring due(Context c) {
        long now = System.currentTimeMillis();
        String today = dateStr(now);
        Ring r = new Ring(today);
        SharedPreferences p = Api.prefs(c);
        long snooze = p.getLong("snooze", 0);
        JSONObject d = day(c, today);
        if (d != null) {
            JSONObject s = d.optJSONObject("settings");
            int offset = s == null ? 0 : s.optInt("offset", 0);
            int stopAfter = s == null ? 120 : s.optInt("stopAfter", 120);
            r.repeat = s == null ? 10 : Math.max(2, s.optInt("repeat", 10));
            JSONArray main = d.optJSONArray("main");
            if (main != null && snooze <= now + TOLERANCE) {
                for (int i = 0; i < main.length(); i++) {
                    JSONObject row = main.optJSONObject(i);
                    if (row == null || isDone(row)) continue;
                    long at = slotAt(today, row.optString("start", "00:00"), offset);
                    if (at <= now + TOLERANCE && now < at + stopAfter * 60000L) r.slots.add(row);
                }
            }
        }
        JSONArray ts = tasks(c);
        for (int i = 0; i < ts.length(); i++) {
            JSONObject t = ts.optJSONObject(i);
            if (t == null) continue;
            long at = taskAlertAt(c, t);
            if (at <= 0 || rung(c, t, at)) continue;
            if (at <= now + TOLERANCE && now - at < 6 * 3600000L) { r.tasks.add(t); r.taskAt.add(at); }
        }
        return r;
    }

    /* ---------- Programmation de la prochaine alarme ---------- */
    public static void reschedule(Context c) {
        long now = System.currentTimeMillis();
        SharedPreferences p = Api.prefs(c);
        long best = Long.MAX_VALUE;
        String label = "";
        if (Api.configured(c)) {
            long snooze = p.getLong("snooze", 0);
            String today = dateStr(now);
            JSONObject d = day(c, today);
            if (d != null) {
                JSONObject s = d.optJSONObject("settings");
                int offset = s == null ? 0 : s.optInt("offset", 0);
                int stopAfter = s == null ? 120 : s.optInt("stopAfter", 120);
                JSONArray main = d.optJSONArray("main");
                if (main != null) {
                    for (int i = 0; i < main.length(); i++) {
                        JSONObject row = main.optJSONObject(i);
                        if (row == null || isDone(row)) continue;
                        long at = slotAt(today, row.optString("start", "00:00"), offset);
                        long cand;
                        if (now < at) cand = at;
                        else if (now < at + stopAfter * 60000L) cand = snooze > now ? snooze : now + SOON;
                        else continue;
                        if (cand < best) { best = cand; label = "Début de service " + row.optString("start"); }
                    }
                }
            }
            JSONArray ts = tasks(c);
            for (int i = 0; i < ts.length(); i++) {
                JSONObject t = ts.optJSONObject(i);
                if (t == null) continue;
                long at = taskAlertAt(c, t);
                if (at <= 0 || rung(c, t, at)) continue;
                long cand;
                if (at > now) cand = at;
                else if (now - at < 6 * 3600000L) cand = now + SOON;
                else continue;
                if (cand < best) { best = cand; label = "Objectif : " + t.optString("title"); }
            }
            if (best == Long.MAX_VALUE) {
                String tomorrow = dateStr(now + 86400000L);
                JSONObject d2 = day(c, tomorrow);
                if (d2 != null) {
                    JSONObject s = d2.optJSONObject("settings");
                    int offset = s == null ? 0 : s.optInt("offset", 0);
                    JSONArray main = d2.optJSONArray("main");
                    if (main != null) {
                        for (int i = 0; i < main.length(); i++) {
                            JSONObject row = main.optJSONObject(i);
                            if (row == null || isDone(row)) continue;
                            long at = slotAt(tomorrow, row.optString("start", "00:00"), offset);
                            if (at > now && at < best) { best = at; label = "Début de service " + row.optString("start") + " (demain)"; }
                        }
                    }
                }
            }
        }
        if (p.getBoolean("test", false)) {
            long t = p.getLong("testAt", now + SOON);
            if (t < best) { best = Math.max(t, now + 1000); label = "Test"; }
        }

        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent fire = PendingIntent.getBroadcast(c, 1, new Intent(c, AlarmReceiver.class).setAction(ACTION_FIRE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent show = PendingIntent.getActivity(c, 2, new Intent(c, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (best != Long.MAX_VALUE) {
            try {
                if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                    am.setAlarmClock(new AlarmManager.AlarmClockInfo(best, show), fire);
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, best, fire);
                }
            } catch (SecurityException e) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, best, fire);
            }
            p.edit().putLong("next", best).putString("nextLabel", label).apply();
        } else {
            am.cancel(fire);
            p.edit().putLong("next", 0).putString("nextLabel", "").apply();
        }
        // Vérification de sécurité toutes les 3 h (nouveau planning, nouveaux objectifs…)
        PendingIntent refresh = PendingIntent.getBroadcast(c, 3, new Intent(c, AlarmReceiver.class).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, now + 3 * 3600000L, refresh);
    }

    /* ---------- Objectifs : date suivante d'une répétition ---------- */
    public static long nextDue(long due, String repeat) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(due);
        long now = System.currentTimeMillis();
        do {
            if ("day".equals(repeat)) c.add(Calendar.DAY_OF_MONTH, 1);
            else if ("week".equals(repeat)) c.add(Calendar.DAY_OF_MONTH, 7);
            else if ("weekday".equals(repeat)) {
                do { c.add(Calendar.DAY_OF_MONTH, 1); }
                while (c.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || c.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY);
            } else return due;
        } while (c.getTimeInMillis() < now);
        return c.getTimeInMillis();
    }
}
