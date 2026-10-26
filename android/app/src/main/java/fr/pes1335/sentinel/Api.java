package fr.pes1335.sentinel;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Échanges avec le serveur Cloudflare de Sentinel. */
public final class Api {
    private Api() {}

    public static final String DEFAULT_URL = "https://sentinel-alert.pesylgom2004.workers.dev";

    public static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("sentinel", Context.MODE_PRIVATE);
    }

    public static String url(Context c) {
        String u = prefs(c).getString("url", DEFAULT_URL);
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    public static String key(Context c) {
        return prefs(c).getString("key", "");
    }

    public static boolean configured(Context c) {
        return key(c).length() > 0;
    }

    public static JSONObject get(Context c, String path) throws Exception {
        return request(c, "GET", path, null);
    }

    public static JSONObject post(Context c, String path, JSONObject body) throws Exception {
        return request(c, "POST", path, body.toString());
    }

    private static JSONObject request(Context c, String method, String path, String body) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url(c) + path).openConnection();
        try {
            con.setRequestMethod(method);
            con.setConnectTimeout(8000);
            con.setReadTimeout(8000);
            con.setUseCaches(false);
            con.setRequestProperty("x-key", key(c));
            con.setRequestProperty("content-type", "application/json");
            if (body != null) {
                con.setDoOutput(true);
                OutputStream os = con.getOutputStream();
                os.write(body.getBytes(StandardCharsets.UTF_8));
                os.close();
            }
            int code = con.getResponseCode();
            InputStream is = code >= 400 ? con.getErrorStream() : con.getInputStream();
            String text = "";
            if (is != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
                is.close();
                text = new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
            JSONObject json = text.length() > 0 ? new JSONObject(text) : new JSONObject();
            if (code >= 400) throw new Exception(json.optString("error", "Erreur " + code));
            return json;
        } finally {
            con.disconnect();
        }
    }
}
