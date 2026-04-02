package com.example.finalfinalproj;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.*;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

public class DriverLoginActivity extends AppCompatActivity {

    EditText    password;
    Button      loginBtn;
    ProgressBar progressBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_driver_login);

        password    = findViewById(R.id.password);
        loginBtn    = findViewById(R.id.loginBtn);
        progressBar = findViewById(R.id.progressBar);

        loginBtn.setOnClickListener(v -> attemptLogin());
    }

    private void attemptLogin() {
        String pass = password.getText().toString().trim();
        if (pass.isEmpty()) {
            Toast.makeText(this, "Enter password", Toast.LENGTH_SHORT).show();
            return;
        }

        setLoading(true);

        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("password", pass);

                ApiClient.post(this, "/driver/login", body, new ApiClient.Callback() {
                    @Override
                    public void onSuccess(JSONObject response) {
                        runOnUiThread(() -> {
                            setLoading(false);
                            try {
                                String token = response.getString("token");

                                SharedPreferences.Editor editor =
                                        getSharedPreferences(Constants.PREFS, MODE_PRIVATE).edit();
                                editor.putString(Constants.KEY_TOKEN,  token);
                                editor.putString(Constants.KEY_ROLE,   "driver");
                                editor.putBoolean(Constants.KEY_LOGGED, true);
                                editor.apply();

                                Toast.makeText(DriverLoginActivity.this,
                                        "Driver Login Success ✅", Toast.LENGTH_SHORT).show();

                                startActivity(new Intent(DriverLoginActivity.this, DriverActivity.class));
                                finish();
                            } catch (Exception e) {
                                Toast.makeText(DriverLoginActivity.this,
                                        "Parse error", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }

                    @Override
                    public void onError(String message) {
                        runOnUiThread(() -> {
                            setLoading(false);
                            Toast.makeText(DriverLoginActivity.this,
                                    "Wrong password ❌", Toast.LENGTH_SHORT).show();
                        });
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setLoading(false);
                    Toast.makeText(this, "Server error ❌", Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void setLoading(boolean loading) {
        loginBtn.setEnabled(!loading);
        if (progressBar != null)
            progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
    }
}
