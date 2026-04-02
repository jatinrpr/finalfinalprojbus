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

        if (userName.isEmpty() || userEmail.isEmpty() || userPass.isEmpty() || userConfirm.isEmpty()) {
            Toast.makeText(this, "Fill all fields", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!userPass.equals(userConfirm)) {
            Toast.makeText(this, "Passwords do not match ❌", Toast.LENGTH_SHORT).show();
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
                            Toast.makeText(SignupActivity.this,
                                    "Signup Failed ❌ " + message, Toast.LENGTH_LONG).show();
                        });
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setLoading(false);
                    Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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
