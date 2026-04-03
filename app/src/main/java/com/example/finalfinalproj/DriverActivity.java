package com.example.finalfinalproj;

import android.Manifest;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Looper;
import android.widget.*;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.FragmentActivity;

import com.google.android.gms.location.*;
import com.google.android.gms.maps.*;
import com.google.android.gms.maps.model.*;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;

public class DriverActivity extends FragmentActivity implements OnMapReadyCallback {

    // ── Location ──────────────────────────────────────────────────────
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback            locationCallback;

    // ── Map ───────────────────────────────────────────────────────────
    private GoogleMap mMap;
    private Marker    myMarker;

    // ── UI ────────────────────────────────────────────────────────────
    private Button   shareBtn;
    private Spinner  busSpinner;
    private TextView tvStatus;
    private TextView tvTimer;      // shows countdown like "1:45:30 remaining"

    private boolean isSharing = false;

    // ── Bus data ──────────────────────────────────────────────────────
    private final List<String> busIds    = new ArrayList<>();
    private final List<String> busLabels = new ArrayList<>();

    // ── 2-Hour auto-timeout (7200 seconds) ───────────────────────────
    private static final long TWO_HOURS_MS = 2 * 60 * 60 * 1000L;
    private CountDownTimer autoStopTimer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_driver);

        shareBtn  = findViewById(R.id.shareBtn);
        busSpinner = findViewById(R.id.busSpinner);
        tvStatus   = findViewById(R.id.tvStatus);
        tvTimer    = findViewById(R.id.tvTimer);

        // Map
        SupportMapFragment mapFragment =
                (SupportMapFragment) getSupportFragmentManager()
                        .findFragmentById(R.id.map);
        if (mapFragment != null) mapFragment.getMapAsync(this);

        // Location
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        }

        // Detect today and load bus list
        fetchBusData(getTodayString());

        // Share / Stop button
        shareBtn.setOnClickListener(v -> {
            if (!isSharing) {
                startSharing();
            } else {
                // Manual stop — no timeout dialog
                stopSharing(false);
            }
        });
    }

    // ── MAP ───────────────────────────────────────────────────────────
    @Override
    public void onMapReady(GoogleMap googleMap) {
        mMap = googleMap;
        LatLng iit = new LatLng(30.9687, 76.4737);
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(iit, 15));
    }

    // ── FETCH TODAY'S BUS LIST ────────────────────────────────────────
    private void fetchBusData(String day) {
        new Thread(() -> {
            try {
                URL url = new URL(Constants.BASE_URL + "/buses?day=" + day);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(8000);

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()));
                StringBuilder result = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) result.append(line);

                JSONArray array = new JSONArray(result.toString());
                busIds.clear();
                busLabels.clear();

                for (int i = 0; i < array.length(); i++) {
                    JSONObject obj = array.getJSONObject(i);
                    busIds.add(obj.getString("busId"));
                    busLabels.add("[" + obj.getString("busId") + "] "
                            + obj.getString("title")
                            + "  " + obj.getString("departure"));
                }

                runOnUiThread(() -> {
                    ArrayAdapter<String> adapter = new ArrayAdapter<>(
                            this,
                            android.R.layout.simple_spinner_item,
                            busLabels);
                    adapter.setDropDownViewResource(
                            android.R.layout.simple_spinner_dropdown_item);
                    busSpinner.setAdapter(adapter);
                });

            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(this,
                                "Could not load buses ❌\nCheck server is running.",
                                Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    // ── START SHARING ─────────────────────────────────────────────────
    private void startSharing() {
        if (getSelectedBusId() == null) {
            Toast.makeText(this, "Please select a bus first", Toast.LENGTH_SHORT).show();
            return;
        }

        // Start GPS updates
        LocationRequest request = LocationRequest.create();
        request.setInterval(3000);
        request.setFastestInterval(2000);
        request.setPriority(Priority.PRIORITY_HIGH_ACCURACY);

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult result) {
                if (result == null) return;
                double lat = result.getLastLocation().getLatitude();
                double lng = result.getLastLocation().getLongitude();
                runOnUiThread(() -> updateDriverMarker(lat, lng));
                sendLocation(getSelectedBusId(), lat, lng);
            }
        };

        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;

        fusedLocationClient.requestLocationUpdates(
                request, locationCallback, Looper.getMainLooper());

        isSharing = true;
        shareBtn.setText("Stop Sharing ⛔");
        if (tvStatus != null) tvStatus.setText("🟢 Sharing live location...");

        // ── Start 2-hour countdown timer ──────────────────────────────
        startAutoStopTimer();

        Toast.makeText(this, "Live location sharing started 🚍", Toast.LENGTH_SHORT).show();
    }

    // ── STOP SHARING ──────────────────────────────────────────────────
    /**
     * @param autoStopped true = triggered by 2-hour timeout → show alert dialog
     *                    false = manual stop by driver → just stop silently
     */
    private void stopSharing(boolean autoStopped) {
        // Stop GPS
        if (locationCallback != null)
            fusedLocationClient.removeLocationUpdates(locationCallback);

        // Cancel countdown timer
        if (autoStopTimer != null) {
            autoStopTimer.cancel();
            autoStopTimer = null;
        }

        // Notify server
        String busId = getSelectedBusId();
        if (busId != null) {
            new Thread(() -> {
                try {
                    JSONObject body = new JSONObject();
                    body.put("busId", busId);
                    ApiClient.postSilent(this, "/stopSharing", body);
                } catch (Exception ignored) {}
            }).start();
        }

        isSharing = false;
        shareBtn.setText("Start Sharing 🚍");
        if (tvStatus != null) tvStatus.setText("🔴 Not sharing");
        if (tvTimer  != null) tvTimer.setText("");

        // ── Show alert only if auto-stopped ───────────────────────────
        if (autoStopped) {
            showAutoStopDialog();
        } else {
            Toast.makeText(this, "Location sharing stopped ⛔", Toast.LENGTH_SHORT).show();
        }
    }

    // ── 2-HOUR COUNTDOWN TIMER ────────────────────────────────────────
    private void startAutoStopTimer() {
        if (autoStopTimer != null) autoStopTimer.cancel();

        autoStopTimer = new CountDownTimer(TWO_HOURS_MS, 1000) {

            @Override
            public void onTick(long millisUntilFinished) {
                // Update countdown display every second
                long hours   = millisUntilFinished / 3600000;
                long minutes = (millisUntilFinished % 3600000) / 60000;
                long seconds = (millisUntilFinished % 60000) / 1000;

                String timeLeft = String.format(Locale.getDefault(),
                        "⏱ Auto-stop in  %02d:%02d:%02d", hours, minutes, seconds);

                runOnUiThread(() -> {
                    if (tvTimer != null) tvTimer.setText(timeLeft);
                });
            }

            @Override
            public void onFinish() {
                // 2 hours reached — auto stop on UI thread
                runOnUiThread(() -> stopSharing(true));
            }
        };

        autoStopTimer.start();
    }

    // ── ALERT DIALOG shown after auto-stop ────────────────────────────
    private void showAutoStopDialog() {
        new AlertDialog.Builder(this)
                .setTitle("⏰ Location Sharing Stopped")
                .setMessage(
                        "Location sharing has been automatically stopped after 2 hours.\n\n" +
                                "✅ Prevention of continuous background location usage\n" +
                                "✅ Improved battery efficiency\n" +
                                "✅ Avoidance of outdated location data\n\n" +
                                "You can restart location sharing by tapping \"Start Sharing\" again."
                )
                .setPositiveButton("OK", (dialog, which) -> dialog.dismiss())
                .setNeutralButton("Restart Sharing", (dialog, which) -> {
                    dialog.dismiss();
                    startSharing();
                })
                .setCancelable(false)   // driver must acknowledge
                .show();
    }

    // ── SEND LOCATION TO SERVER ───────────────────────────────────────
    private void sendLocation(String busId, double lat, double lng) {
        if (busId == null) return;
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("busId", busId);
                body.put("lat",   lat);
                body.put("lng",   lng);
                ApiClient.postSilent(this, "/updateLocation", body);
            } catch (Exception ignored) {}
        }).start();
    }

    // ── UPDATE MAP MARKER ─────────────────────────────────────────────
    private void updateDriverMarker(double lat, double lng) {
        LatLng pos = new LatLng(lat, lng);
        if (myMarker == null) {
            myMarker = mMap.addMarker(new MarkerOptions()
                    .position(pos)
                    .title("You 📍"));
        } else {
            myMarker.setPosition(pos);
        }
        mMap.animateCamera(CameraUpdateFactory.newLatLng(pos));
    }

    // ── HELPERS ───────────────────────────────────────────────────────
    private String getSelectedBusId() {
        int idx = busSpinner.getSelectedItemPosition();
        return (idx >= 0 && idx < busIds.size()) ? busIds.get(idx) : null;
    }

    private String getTodayString() {
        int day = Calendar.getInstance().get(Calendar.DAY_OF_WEEK);
        if (day == Calendar.MONDAY)    return "Monday";
        if (day == Calendar.TUESDAY)   return "Tuesday";
        if (day == Calendar.WEDNESDAY) return "Wednesday";
        if (day == Calendar.THURSDAY)  return "Thursday";
        if (day == Calendar.FRIDAY)    return "Friday";
        if (day == Calendar.SATURDAY)  return "Saturday";
        return "Sunday";
    }

    // ── LIFECYCLE ─────────────────────────────────────────────────────
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isSharing) stopSharing(false);
    }
}
