package com.example.finalfinalproj;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.Button;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 🔥 AUTO LOGIN CHECK
        SharedPreferences prefs = getSharedPreferences("MyApp", MODE_PRIVATE);

        boolean isLoggedIn = prefs.getBoolean("isLoggedIn", false);
        String userType = prefs.getString("userType", "");

        if (isLoggedIn && getIntent().getExtras() == null) {
            if (userType.equals("student")) {
                startActivity(new Intent(this, HomeActivity.class));
                finish();
                return;
            }
        }

        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        ImageView bus = findViewById(R.id.busImage);
        Button studentBtn = findViewById(R.id.studentBtn);
        Button driverBtn = findViewById(R.id.driverBtn);
        Button btnCalendar = findViewById(R.id.btnCalendar);

        // 🔥 HIDE ALL BUTTONS INITIALLY
        studentBtn.setAlpha(0f);
        driverBtn.setAlpha(0f);
        btnCalendar.setAlpha(0f); // ✅ FIXED

        // 🎓 STUDENT CLICK
        studentBtn.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, LoginActivity.class);
            startActivity(intent);
        });

        // 🚌 DRIVER CLICK
        driverBtn.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, DriverLoginActivity.class);
            startActivity(intent);
        });

        // 📄 BUS CALENDAR CLICK
        btnCalendar.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse("https://www.iitrpr.ac.in/downloads/sites/default/files/January/Revised%20Bus%20Timing%20W.E.F%2002-01-2026.pdf"));
            startActivity(intent);
        });

        // 🚍 ANIMATION
        bus.post(() -> {

            float screenWidth = getResources().getDisplayMetrics().widthPixels;
            float busWidth = bus.getWidth();

            float centerX = (screenWidth / 2f) - (busWidth / 2f);

            bus.setX(-busWidth);

            bus.animate()
                    .x(centerX)
                    .setDuration(2000)
                    .start();

            bus.postDelayed(() -> {

                // 🎓 Student button
                studentBtn.animate()
                        .alpha(1f)
                        .setDuration(800)
                        .start();

                // 🚌 Driver button
                driverBtn.animate()
                        .alpha(1f)
                        .setDuration(800)
                        .setStartDelay(200)
                        .start();

                // 📄 Calendar button
                btnCalendar.animate()
                        .alpha(1f)
                        .setDuration(800)
                        .setStartDelay(400)
                        .start();

            }, 1400);
        });
    }
}