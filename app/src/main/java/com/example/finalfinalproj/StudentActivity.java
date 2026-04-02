package com.example.finalfinalproj;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

public class StudentActivity extends AppCompatActivity {

    Button loginBtn, signupBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_student);

        // 🔘 Buttons
        loginBtn = findViewById(R.id.loginBtn);
        signupBtn = findViewById(R.id.signupBtn);

        // 👉 LOGIN → OPEN LOGIN PAGE
        loginBtn.setOnClickListener(v -> {
            Intent intent = new Intent(StudentActivity.this, LoginActivity.class);
            startActivity(intent);
        });

        // 👉 SIGNUP → OPEN SIGNUP PAGE
        signupBtn.setOnClickListener(v -> {
            Intent intent = new Intent(StudentActivity.this, SignupActivity.class);
            startActivity(intent);
        });
    }
}