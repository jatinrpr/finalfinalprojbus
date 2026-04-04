package com.example.finalfinalproj;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONObject;

public class SignupActivity extends AppCompatActivity {

    EditText    etName, etEmail, etPassword, etConfirm;
    Button      btnSignup;
    ProgressBar progressBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_signup);

        etName      = findViewById(R.id.name);
        etEmail     = findViewById(R.id.email);
        etPassword  = findViewById(R.id.password);
        etConfirm   = findViewById(R.id.confirmPassword);
        btnSignup   = findViewById(R.id.signupBtn);
        progressBar = findViewById(R.id.progressBar);

        btnSignup.setOnClickListener(v -> attemptSignup());
    }

    private void attemptSignup() {
        String name    = etName.getText().toString().trim();
        String email   = etEmail.getText().toString().trim().toLowerCase();
        String pass    = etPassword.getText().toString().trim();
        String confirm = etConfirm.getText().toString().trim();

        // ── Validations ───────────────────────────────────────────
        if (name.isEmpty()) {
            etName.setError("Enter your name");
            etName.requestFocus();
            return;
        }
        if (email.isEmpty()) {
            etEmail.setError("Enter your Gmail");
            etEmail.requestFocus();
            return;
        }
        if (!email.endsWith("@gmail.com")) {
            etEmail.setError("Only Gmail allowed (e.g. name@gmail.com)");
            etEmail.requestFocus();
            return;
        }
        if (pass.isEmpty() || pass.length() < 6) {
            etPassword.setError("Password must be at least 6 characters");
            etPassword.requestFocus();
            return;
        }
        if (!pass.equals(confirm)) {
            etConfirm.setError("Passwords do not match");
            etConfirm.requestFocus();
            return;
        }

        setLoading(true);

        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("name",     name);
                body.put("email",    email);
                body.put("password", pass);

                ApiClient.post(this, "/signup", body, new ApiClient.Callback() {
                    @Override
                    public void onSuccess(JSONObject response) {
                        runOnUiThread(() -> {
                            setLoading(false);
                            Toast.makeText(SignupActivity.this,
                                    "Account created successfully ✅",
                                    Toast.LENGTH_SHORT).show();
                            startActivity(new Intent(SignupActivity.this, LoginActivity.class));
                            finish();
                        });
                    }

                    @Override
                    public void onError(String message) {
                        runOnUiThread(() -> {
                            setLoading(false);
                            if (message.contains("already registered")) {
                                etEmail.setError("This Gmail is already registered");
                                etEmail.requestFocus();
                            } else {
                                Toast.makeText(SignupActivity.this,
                                        "Signup Failed: " + message,
                                        Toast.LENGTH_LONG).show();
                            }
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

    private void setLoading(boolean loading) {
        btnSignup.setEnabled(!loading);
        if (progressBar != null)
            progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
    }
}