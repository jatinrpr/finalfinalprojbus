package com.example.finalfinalproj;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
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

/**
 * Driver screen.
 * Driver selects their bus from spinner, taps Start Sharing.
 * Location sent to server every 3 seconds via POST /updateLocation (with JWT).
 * Server stores it in MongoDB AND emits Socket.IO event to all watching students.
 * Server also checks if bus is within 500m of a stop → sends FCM push notification.
 */
public class DriverActivity extends FragmentActivity implements OnMapReadyCallback {

    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback            locationCallback;
    private GoogleMap                   mMap;
    private Marker                      myMarker;

    private Button  shareBtn;
    private Spinner busSpinner;
    private TextView tvStatus;
    private boolean isSharing = false;

    private final List<String> busIds   = new ArrayList<>();
    private final List<String> busLabels = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_driver);

        shareBtn  = findViewById(R.id.shareBtn);
        busSpinner = findViewById(R.id.busSpinner);
        tvStatus   = findViewById(R.id.tvStatus);   // add TextView to layout for status

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

        // Auto-detect today
        Calendar cal = Calendar.getInstance();
        int day = cal.get(Calendar.DAY_OF_WEEK);
        String today;
        if      (day == Calendar.MONDAY)    today = "Monday";
        else if (day == Calendar.TUESDAY)   today = "Tuesday";
        else if (day == Calendar.WEDNESDAY) today = "Wednesday";
        else if (day == Calendar.THURSDAY)  today = "Thursday";
        else if (day == Calendar.FRIDAY)    today = "Friday";
        else if (day == Calendar.SATURDAY)  today = "Saturday";
        else                                today = "Sunday";

        fetchBusData(today);

        shareBtn.setOnClickListener(v -> {
            if (!isSharing) {
                startSharing();
            } else {
                stopSharing();
            }
        });
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        mMap = googleMap;
        LatLng iit = new LatLng(30.9687, 76.4737);
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(iit, 15));
    }

    // ── Fetch today's bus list ────────────────────────────────────────
    private void fetchBusData(String day) {
        new Thread(() -> {
            try {
                URL url = new URL(Constants.BASE_URL + "/buses?day=" + day);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");

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
                            + obj.getString("title") + " - " + obj.getString("departure"));
                }

                runOnUiThread(() -> {
                    ArrayAdapter<String> adapter = new ArrayAdapter<>(
                            this, android.R.layout.simple_spinner_item, busLabels);
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                    busSpinner.setAdapter(adapter);
                });

            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(this, "Could not load buses ❌", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    // ── Start sharing ─────────────────────────────────────────────────
    private void startSharing() {
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

        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper());

        isSharing = true;
        shareBtn.setText("Stop Sharing ⛔");
        if (tvStatus != null) tvStatus.setText("🟢 Sharing live location...");
        Toast.makeText(this, "Live location sharing started 🚍", Toast.LENGTH_SHORT).show();
    }

    // ── Stop sharing ──────────────────────────────────────────────────
    private void stopSharing() {
        if (locationCallback != null)
            fusedLocationClient.removeLocationUpdates(locationCallback);

        // Notify server so students immediately see "not started"
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
        Toast.makeText(this, "Sharing stopped ⛔", Toast.LENGTH_SHORT).show();
    }

    // ── Send location to server ───────────────────────────────────────
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

    private void updateDriverMarker(double lat, double lng) {
        LatLng pos = new LatLng(lat, lng);
        if (myMarker == null) {
            myMarker = mMap.addMarker(new MarkerOptions().position(pos).title("You 📍"));
        } else {
            myMarker.setPosition(pos);
        }
        mMap.animateCamera(CameraUpdateFactory.newLatLng(pos));
    }

    private String getSelectedBusId() {
        int idx = busSpinner.getSelectedItemPosition();
        return (idx >= 0 && idx < busIds.size()) ? busIds.get(idx) : null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isSharing) stopSharing();
    }
}
