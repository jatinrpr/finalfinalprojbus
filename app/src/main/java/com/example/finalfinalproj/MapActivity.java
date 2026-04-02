package com.example.finalfinalproj;

import android.animation.ValueAnimator;
import android.graphics.Color;
import android.os.Bundle;
import android.view.animation.LinearInterpolator;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.FragmentActivity;

import com.google.android.gms.maps.*;
import com.google.android.gms.maps.model.*;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import io.socket.client.IO;
import io.socket.client.Socket;

/**
 * Live map for a specific bus.
 *
 * Features:
 *  ✅ Socket.IO real-time location (instant updates)
 *  ✅ Polyline — draws the bus's travelled route
 *  ✅ Smooth animated marker movement
 *  ✅ ETA label at bottom
 *
 * 📋 Add to app/build.gradle dependencies:
 *    implementation 'io.socket:socket.io-client:2.1.0'
 */
public class MapActivity extends FragmentActivity implements OnMapReadyCallback {

    private GoogleMap mMap;
    private Marker    busMarker;
    private Polyline  routePolyline;
    private String    busId;
    private String    busTitle;
    private TextView  tvEta;

    private Socket    socket;
    private LatLng    lastLatLng = null;

    // Route points list for Polyline
    private final List<LatLng> routePoints = new ArrayList<>();

    // IIT Ropar main gate (used for ETA)
    private static final double GATE_LAT = 30.9687;
    private static final double GATE_LNG = 76.4737;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_map);

        busId    = getIntent().getStringExtra("busId");
        busTitle = getIntent().getStringExtra("title");
        tvEta    = findViewById(R.id.tvEta);

        SupportMapFragment mapFragment =
                (SupportMapFragment) getSupportFragmentManager()
                        .findFragmentById(R.id.map);
        if (mapFragment != null) mapFragment.getMapAsync(this);
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        mMap = googleMap;
        mMap.setMapType(GoogleMap.MAP_TYPE_NORMAL);
        mMap.getUiSettings().setZoomControlsEnabled(true);

        double startLat = getIntent().getDoubleExtra("lat", GATE_LAT);
        double startLng = getIntent().getDoubleExtra("lng", GATE_LNG);
        lastLatLng = new LatLng(startLat, startLng);

        // Place initial marker
        busMarker = mMap.addMarker(new MarkerOptions()
                .position(lastLatLng)
                .title(busTitle != null ? busTitle : "Bus")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)));

        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(lastLatLng, 16));

        // Load existing route from server, then start Socket.IO
        loadRouteHistory();
        connectSocket();
    }

    // ── Load existing route history (drawn as Polyline immediately) ───
    private void loadRouteHistory() {
        new Thread(() -> {
            try {
                URL url = new URL(Constants.BASE_URL + "/getLocation?busId=" + busId);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()));
                StringBuilder result = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) result.append(line);

                JSONObject obj = new JSONObject(result.toString());
                if (!obj.getBoolean("started")) {
                    runOnUiThread(() -> {
                        if (tvEta != null) tvEta.setText("Bus has not started yet 🚫");
                    });
                    return;
                }

                // Parse route array
                JSONArray route = obj.optJSONArray("route");
                if (route != null) {
                    for (int i = 0; i < route.length(); i++) {
                        JSONObject pt = route.getJSONObject(i);
                        routePoints.add(new LatLng(pt.getDouble("lat"), pt.getDouble("lng")));
                    }
                }

                double lat = obj.getDouble("lat");
                double lng = obj.getDouble("lng");

                runOnUiThread(() -> {
                    drawPolyline();
                    animateMarker(new LatLng(lat, lng));
                    fetchETA(lat, lng);
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (tvEta != null) tvEta.setText("Could not connect to server ❌");
                });
            }
        }).start();
    }

    // ── Socket.IO — real-time location updates ────────────────────────
    private void connectSocket() {
        try {
            IO.Options opts = new IO.Options();
            opts.reconnection         = true;
            opts.reconnectionAttempts = Integer.MAX_VALUE;
            opts.reconnectionDelay    = 1000;

            socket = IO.socket(Constants.SOCKET_URL, opts);

            socket.on(Socket.EVENT_CONNECT, args -> {
                // Join this bus's room
                socket.emit("watchBus", busId);
                runOnUiThread(() -> Toast.makeText(this,
                        "Live tracking active 🟢", Toast.LENGTH_SHORT).show());
            });

            socket.on("locationUpdate", args -> {
                try {
                    JSONObject data = new JSONObject(args[0].toString());
                    double lat = data.getDouble("lat");
                    double lng = data.getDouble("lng");

                    routePoints.add(new LatLng(lat, lng));

                    runOnUiThread(() -> {
                        animateMarker(new LatLng(lat, lng));
                        drawPolyline();
                        fetchETA(lat, lng);
                    });
                } catch (Exception ignored) {}
            });

            socket.on("busStopped", args ->
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Bus has stopped sharing 🚫", Toast.LENGTH_LONG).show();
                        if (tvEta != null) tvEta.setText("Bus stopped 🚫");
                    })
            );

            socket.on(Socket.EVENT_DISCONNECT, args ->
                    runOnUiThread(() -> Toast.makeText(this,
                            "Reconnecting...", Toast.LENGTH_SHORT).show())
            );

            socket.connect();

        } catch (Exception e) {
            Toast.makeText(this, "Socket error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // ── Draw / update Polyline ─────────────────────────────────────────
    private void drawPolyline() {
        if (routePoints.isEmpty()) return;

        if (routePolyline == null) {
            routePolyline = mMap.addPolyline(new PolylineOptions()
                    .addAll(routePoints)
                    .width(8f)
                    .color(Color.parseColor("#1565C0"))   // blue route line
                    .geodesic(true));
        } else {
            routePolyline.setPoints(routePoints);
        }
    }

    // ── Smooth marker animation ────────────────────────────────────────
    private void animateMarker(LatLng newPos) {
        if (busMarker == null) return;
        if (lastLatLng == null) {
            busMarker.setPosition(newPos);
            lastLatLng = newPos;
            return;
        }

        LatLng from = lastLatLng;
        lastLatLng  = newPos;

        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(900);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(anim -> {
            float  t   = (float) anim.getAnimatedValue();
            double lat = from.latitude  + t * (newPos.latitude  - from.latitude);
            double lng = from.longitude + t * (newPos.longitude - from.longitude);
            busMarker.setPosition(new LatLng(lat, lng));
        });
        animator.start();

        mMap.animateCamera(CameraUpdateFactory.newLatLng(newPos));
    }

    // ── ETA calculation ────────────────────────────────────────────────
    private void fetchETA(double busLat, double busLng) {
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("busLat",  busLat);
                body.put("busLng",  busLng);
                body.put("stopLat", GATE_LAT);
                body.put("stopLng", GATE_LNG);

                ApiClient.post(this, "/getETA", body, new ApiClient.Callback() {
                    @Override
                    public void onSuccess(JSONObject response) {
                        runOnUiThread(() -> {
                            try {
                                int etaMins = response.getInt("etaMinutes");
                                int distM   = response.getInt("distanceMeters");
                                if (tvEta != null) {
                                    if (distM < 80) {
                                        tvEta.setText("🏁 Bus has arrived!");
                                    } else {
                                        tvEta.setText("🕒 ETA: ~" + etaMins + " min  |  " + distM + " m away");
                                    }
                                }
                            } catch (Exception ignored) {}
                        });
                    }
                    @Override
                    public void onError(String message) {}
                });
            } catch (Exception ignored) {}
        }).start();
    }

    // ── Lifecycle ──────────────────────────────────────────────────────
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (socket != null) {
            socket.emit("stopWatching", busId);
            socket.disconnect();
            socket.close();
        }
    }
}
