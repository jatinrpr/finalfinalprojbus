package com.example.finalfinalproj;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONObject;

public class SignupActivity extends AppCompatActivity {

    // ── Step 1 views ─────────────────────────
    private LinearLayout layoutStep1;
    private EditText etName, etEmail, etPassword, etConfirm;
    private Button btnSendOtp;

    // ── Step 2 views ─────────────────────────
    private LinearLayout layoutStep2;
    private TextView tvOtpSentTo;
    private EditText etOtp;
    private Button btnVerifyOtp, btnResendOtp;

    private ProgressBar progressBar;
    private String pendingEmail;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_signup);

        layoutStep1 = findViewById(R.id.layoutStep1);
        etName = findViewById(R.id.name);
        etEmail = findViewById(R.id.email);
        etPassword = findViewById(R.id.password);
        etConfirm = findViewById(R.id.confirmPassword);
        btnSendOtp = findViewById(R.id.signupBtn);

        layoutStep2 = findViewById(R.id.layoutStep2);
        tvOtpSentTo = findViewById(R.id.tvOtpSentTo);
        etOtp = findViewById(R.id.etOtp);
        btnVerifyOtp = findViewById(R.id.btnVerifyOtp);
        btnResendOtp = findViewById(R.id.btnResendOtp);

        progressBar = findViewById(R.id.progressBar);

        layoutStep1.setVisibility(View.VISIBLE);
        layoutStep2.setVisibility(View.GONE);

        btnSendOtp.setOnClickListener(v -> sendOtp());
        btnVerifyOtp.setOnClickListener(v -> verifyOtp());
        btnResendOtp.setOnClickListener(v -> sendOtp());
    }

    // ── STEP 1: Send OTP ─────────────────────
    private void sendOtp() {
        String name = etName.getText().toString().trim();
        String email = etEmail.getText().toString().trim().toLowerCase();
        String pass = etPassword.getText().toString().trim();
        String confirm = etConfirm.getText().toString().trim();

        if (name.isEmpty() || email.isEmpty() || pass.isEmpty() || confirm.isEmpty()) {
            Toast.makeText(SignupActivity.this, "Please fill all fields", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!email.endsWith("@gmail.com")) {
            etEmail.setError("Only Gmail allowed");
            return;
        }

        if (pass.length() < 6) {
            etPassword.setError("Password must be at least 6 characters");
            return;
        }

        if (!pass.equals(confirm)) {
            etConfirm.setError("Passwords do not match");
            return;
        }

        setLoading(true);
        pendingEmail = email;

        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("name", name);
                body.put("email", email);
                body.put("password", pass);

                ApiClient.post(SignupActivity.this, "/sendOtp", body, new ApiClient.Callback() {

                    @Override
                    public void onSuccess(JSONObject response) {
                        runOnUiThread(() -> {
                            setLoading(false);

                            layoutStep1.setVisibility(View.GONE);
                            layoutStep2.setVisibility(View.VISIBLE);

                            tvOtpSentTo.setText("OTP sent to " + pendingEmail);

                            Toast.makeText(SignupActivity.this,
                                    "OTP sent to your Gmail! ✅",
                                    Toast.LENGTH_LONG).show();
                        });
                    }

                    @Override
                    public void onError(String message) {
                        runOnUiThread(() -> {
                            setLoading(false);

                            if (message.contains("already registered")) {
                                etEmail.setError("Email already registered");
                            } else {
                                Toast.makeText(SignupActivity.this,
                                        "Failed: " + message,
                                        Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    setLoading(false);
                    Toast.makeText(SignupActivity.this,
                            "Server Error ❌",
                            Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    // ── STEP 2: Verify OTP ───────────────────
    private void verifyOtp() {
        String otp = etOtp.getText().toString().trim();

        if (otp.length() != 6) {
            etOtp.setError("Enter valid OTP");
            return;
        }

        setLoading(true);

        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("email", pendingEmail);
                body.put("otp", otp);

                ApiClient.post(SignupActivity.this, "/verifyOtp", body, new ApiClient.Callback() {

                    @Override
                    public void onSuccess(JSONObject response) {
                        runOnUiThread(() -> {
                            setLoading(false);

                            Toast.makeText(SignupActivity.this,
                                    "Account created ✅",
                                    Toast.LENGTH_LONG).show();

                            startActivity(new Intent(SignupActivity.this, LoginActivity.class));
                            finish();
                        });
                    }

                    @Override
                    public void onError(String message) {
                        runOnUiThread(() -> {
                            setLoading(false);

                            Toast.makeText(SignupActivity.this,
                                    "Error: " + message,
                                    Toast.LENGTH_LONG).show();
                        });
                    }
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    setLoading(false);
                    Toast.makeText(SignupActivity.this,
                            "Server Error ❌",
                            Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void setLoading(boolean loading) {
        btnSendOtp.setEnabled(!loading);
        if (btnVerifyOtp != null) btnVerifyOtp.setEnabled(!loading);
        if (progressBar != null)
            progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
    }
}