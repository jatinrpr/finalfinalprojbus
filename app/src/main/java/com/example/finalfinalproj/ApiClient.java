package com.example.finalfinalproj;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Lightweight HTTP helper.
 * Always call from a background thread (new Thread or AsyncTask).
 */
public class ApiClient {

    public interface Callback {
        void onSuccess(JSONObject response);
        void onError(String message);
    }

    // ── GET ────────────────────────────────────────────────────────────
    public static void get(Context ctx, String path, Callback cb) {
        try {
            URL url = new URL(Constants.BASE_URL + path);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            addAuthHeader(ctx, conn);

            int code = conn.getResponseCode();
            String body = readBody(conn);

            if (code >= 200 && code < 300) {
                cb.onSuccess(new JSONObject(body));
            } else {
                cb.onError("HTTP " + code + ": " + body);
            }
        } catch (Exception e) {
            cb.onError(e.getMessage());
        }
    }

    // ── POST ───────────────────────────────────────────────────────────
    public static void post(Context ctx, String path, JSONObject body, Callback cb) {
        try {
            URL url = new URL(Constants.BASE_URL + path);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            addAuthHeader(ctx, conn);

            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.close();

            int code = conn.getResponseCode();
            String resp = readBody(conn);

            if (code >= 200 && code < 300) {
                cb.onSuccess(new JSONObject(resp));
            } else {
                JSONObject err = new JSONObject(resp);
                cb.onError(err.optString("error", "Request failed"));
            }
        } catch (Exception e) {
            cb.onError(e.getMessage());
        }
    }

    // ── Fire-and-forget POST (location updates) ────────────────────────
    public static void postSilent(Context ctx, String path, JSONObject body) {
        try {
            URL url = new URL(Constants.BASE_URL + path);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            addAuthHeader(ctx, conn);

            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.close();

            conn.getResponseCode(); // trigger the request
        } catch (Exception ignored) {}
    }

    // ── Helpers ────────────────────────────────────────────────────────
    private static void addAuthHeader(Context ctx, HttpURLConnection conn) {
        SharedPreferences prefs = ctx.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE);
        String token = prefs.getString(Constants.KEY_TOKEN, "");
        if (!token.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
    }

    private static String readBody(HttpURLConnection conn) throws Exception {
        BufferedReader reader;
        try {
            reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        } catch (Exception e) {
            reader = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
        }
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        return sb.toString();
    }
}
