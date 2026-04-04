package com.example.finalfinalproj;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.*;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

public class SignupActivity extends AppCompatActivity {

    EditText    name, email, password, confirmPassword;
    Button      signupBtn;
    ProgressBar progressBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_signup);

        name            = findViewById(R.id.name);
        email           = findViewById(R.id.email);
        password        = findViewById(R.id.password);
        confirmPassword = findViewById(R.id.confirmPassword);
        signupBtn       = findViewById(R.id.signupBtn);
        progressBar     = findViewById(R.id.progressBar);

        signupBtn.setOnClickListener(v -> attemptSignup());
    }

    private void attemptSignup() {
        String userName    = name.getText().toString().trim();
        String userEmail   = email.getText().toString().trim().toLowerCase();
        String userPass    = password.getText().toString().trim();
        String userConfirm = confirmPassword.getText().toString().trim();

        // ── Validation ────────────────────────────────────────────
        if (userName.isEmpty() || userEmail.isEmpty() ||
                userPass.isEmpty() || userConfirm.isEmpty()) {
            Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show();
            return;
        }

        // ✅ Gmail only check
        if (!userEmail.endsWith("@gmail.com")) {
            email.setError("Only Gmail addresses are allowed (e.g. name@gmail.com)");
            email.requestFocus();
            return;
        }

        // ✅ Basic email format check
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(userEmail).matches()) {
            email.setError("Enter a valid Gmail address");
            email.requestFocus();
            return;
        }

        // ✅ Password length
        if (userPass.length() < 6) {
            password.setError("Password must be at least 6 characters");
            password.requestFocus();
            return;
        }

        // ✅ Password match
        if (!userPass.equals(userConfirm)) {
            confirmPassword.setError("Passwords do not match ❌");
            confirmPassword.requestFocus();
            return;
        }

        setLoading(true);

        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("name",     userName);
                body.put("email",    userEmail);
                body.put("password", userPass);

                ApiClient.post(this, "/signup", body, new ApiClient.Callback() {
                    @Override
                    public void onSuccess(JSONObject response) {
                        runOnUiThread(() -> {
                            setLoading(false);
                            Toast.makeText(SignupActivity.this,
                                    "Signup Successful ✅", Toast.LENGTH_SHORT).show();
                            startActivity(new Intent(SignupActivity.this, LoginActivity.class));
                            finish();
                        });
                    }

                    @Override
                    public void onError(String message) {
                        runOnUiThread(() -> {
                            setLoading(false);
                            // Show specific error under email field if duplicate
                            if (message.contains("already registered")) {
                                email.setError("This Gmail is already registered");
                                email.requestFocus();
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
        signupBtn.setEnabled(!loading);
        if (progressBar != null)
            progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
    }
}