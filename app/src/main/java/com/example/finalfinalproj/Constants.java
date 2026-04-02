package com.example.finalfinalproj;

/**
 * 🔧 CHANGE BASE_URL to your Render/Railway URL after deployment.
 *
 * Local emulator:   "http://10.0.2.2:3000"
 * Local real device:"http://192.168.X.X:3000"   (your PC IP)
 * Render cloud:     "https://smart-bus-tracking.onrender.com"
 * Railway cloud:    "https://smart-bus-tracking.up.railway.app"
 */
public class Constants {

    // ── 🔥 CHANGE THIS ONE LINE ONLY ───────────────────────────────
    public static final String BASE_URL    = "https://YOUR-APP.onrender.com";
    public static final String SOCKET_URL  = "https://YOUR-APP.onrender.com";
    // ───────────────────────────────────────────────────────────────

    // SharedPreferences keys
    public static final String PREFS      = "MyApp";
    public static final String KEY_TOKEN  = "jwtToken";
    public static final String KEY_NAME   = "userName";
    public static final String KEY_ROLE   = "userType";
    public static final String KEY_LOGGED = "isLoggedIn";
}