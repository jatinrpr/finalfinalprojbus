package com.example.finalfinalproj;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.*;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONObject;

public class LoginActivity extends AppCompatActivity {

    EditText    email, password;
    Button      loginBtn;
    TextView    createAccount;
    ProgressBar progressBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        email         = findViewById(R.id.email);
        password      = findViewById(R.id.password);
        loginBtn      = findViewById(R.id.loginBtn);
        createAccount = findViewById(R.id.createAccount);
        progressBar   = findViewById(R.id.progressBar);

        loginBtn.setOnClickListener(v -> attemptLogin());
        createAccount.setOnClickListener(v ->
                startActivity(new Intent(LoginActivity.this, SignupActivity.class)));
    }

    private void attemptLogin() {
        String userEmail = email.getText().toString().trim().toLowerCase();
        String userPass  = password.getText().toString().trim();

        if (userEmail.isEmpty() || userPass.isEmpty()) {
            Toast.makeText(this, "Fill all fields", Toast.LENGTH_SHORT).show();
            return;
        }

        setLoading(true);

        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("email",    userEmail);
                body.put("password", userPass);

                ApiClient.post(this, "/login", body, new ApiClient.Callback() {
                    @Override
                    public void onSuccess(JSONObject response) {
                        runOnUiThread(() -> {
                            setLoading(false);
                            try {
                                String token = response.getString("token");
                                String name  = response.getString("name");

                                SharedPreferences.Editor editor =
                                        getSharedPreferences(Constants.PREFS, MODE_PRIVATE).edit();
                                editor.putString(Constants.KEY_TOKEN,  token);
                                editor.putString(Constants.KEY_NAME,   name);
                                editor.putString(Constants.KEY_ROLE,   "student");
                                editor.putBoolean(Constants.KEY_LOGGED, true);
                                editor.apply();

                                // 🔔 Subscribe to general bus notifications topic
                                subscribeToBusNotifications();

                                Toast.makeText(LoginActivity.this,
                                        "Welcome, " + name + " ✅", Toast.LENGTH_SHORT).show();

                                startActivity(new Intent(LoginActivity.this, HomeActivity.class));
                                finish();
                            } catch (Exception e) {
                                Toast.makeText(LoginActivity.this,
                                        "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                            }
                        });
                    }

                    @Override
                    public void onError(String message) {
                        runOnUiThread(() -> {
                            setLoading(false);
                            Toast.makeText(LoginActivity.this,
                                    "Login Failed ❌ " + message, Toast.LENGTH_LONG).show();
                        });
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setLoading(false);
                    Toast.makeText(this, "Server Error ❌", Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    /**
     * Subscribe to all bus topics so the student gets notifications
     * for any bus that comes within 500m of a stop.
     *
     * Subscribes to bus_BUS001 … bus_BUS024
     */
    private void subscribeToBusNotifications() {
        for (int i = 1; i <= 24; i++) {
            String busId = String.format("BUS%03d", i);
            FirebaseMessaging.getInstance()
                    .subscribeToTopic("bus_" + busId)
                    .addOnCompleteListener(task -> {
                        // silent — no UI needed
                    });
        }

        // Also send FCM token to backend so server can target this device directly
        FirebaseMessaging.getInstance().getToken()
                .addOnCompleteListener(task -> {
                    if (!task.isSuccessful()) return;
                    String fcmToken = task.getResult();
                    new Thread(() -> {
                        try {
                            JSONObject body = new JSONObject();
                            body.put("fcmToken", fcmToken);
                            ApiClient.postSilent(this, "/updateFcmToken", body);
                        } catch (Exception ignored) {}
                    }).start();
                });
    }

    private void setLoading(boolean loading) {
        loginBtn.setEnabled(!loading);
        if (progressBar != null)
            progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
    }
}
