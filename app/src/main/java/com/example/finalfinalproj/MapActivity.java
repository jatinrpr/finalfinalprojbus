package com.example.finalfinalproj;

import android.animation.ValueAnimator;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.*;

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

public class MapActivity extends FragmentActivity implements OnMapReadyCallback {

    // ── UI ────────────────────────────────────────────────────────
    private GoogleMap    mMap;
    private Marker       busMarker;
    private Polyline     routePolyline;
    private TextView     tvEta, tvBusTitle, tvNotSharingMsg;
    private LinearLayout layoutTracking, layoutNotSharing;
    private Spinner      stopSpinner;
    private Button       btnBack;

    // ── Data ──────────────────────────────────────────────────────
    private String  busId, busTitle, departure;
    private ArrayList<String> stopNames = new ArrayList<>();
    private ArrayList<String> stopTimes = new ArrayList<>();

    // Stop coordinates fetched from server
    private final List<double[]> stopCoords  = new ArrayList<>(); // [lat, lng] per stop
    private final List<LatLng>   routePoints = new ArrayList<>();

    private LatLng  lastLatLng = null;
    private volatile boolean isRunning = true;
    private boolean mapReady  = false;

    // Currently selected stop index for ETA
    private int selectedStopIndex = 0;

    // IIT Ropar gate fallback
    private static final double GATE_LAT = 30.9687;
    private static final double GATE_LNG = 76.4737;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_map);

        // ── Get intent data ───────────────────────────────────────
        busId     = getIntent().getStringExtra("busId");
        busTitle  = getIntent().getStringExtra("title");
        departure = getIntent().getStringExtra("departure");
        stopNames = getIntent().getStringArrayListExtra("stopNames");
        stopTimes = getIntent().getStringArrayListExtra("stopTimes");
        if (stopNames == null) stopNames = new ArrayList<>();
        if (stopTimes == null) stopTimes = new ArrayList<>();

        // ── Bind views ────────────────────────────────────────────
        tvEta            = findViewById(R.id.tvEta);
        tvBusTitle       = findViewById(R.id.tvBusTitle);
        tvNotSharingMsg  = findViewById(R.id.tvNotSharingMsg);
        layoutTracking   = findViewById(R.id.layoutTracking);
        layoutNotSharing = findViewById(R.id.layoutNotSharing);
        stopSpinner      = findViewById(R.id.stopSpinner);
        btnBack          = findViewById(R.id.btnBack);

        if (tvBusTitle != null)
            tvBusTitle.setText(busTitle != null ? busTitle : "Bus");

        if (btnBack != null)
            btnBack.setOnClickListener(v -> finish());

        // ── Stops spinner setup ───────────────────────────────────
        setupStopsSpinner();

        // ── Map ───────────────────────────────────────────────────
        SupportMapFragment mapFragment =
                (SupportMapFragment) getSupportFragmentManager()
                        .findFragmentById(R.id.map);
        if (mapFragment != null) mapFragment.getMapAsync(this);
    }

    // ── Build stops dropdown ──────────────────────────────────────
    private void setupStopsSpinner() {
        // Build display labels  "Railway Station — 08:30 AM"
        ArrayList<String> labels = new ArrayList<>();
        for (int i = 0; i < stopNames.size(); i++) {
            String time = (i < stopTimes.size()) ? stopTimes.get(i) : "";
            labels.add("📍 " + stopNames.get(i) + "  (" + time + ")");
        }

        if (labels.isEmpty()) labels.add("No stops available");

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        stopSpinner.setAdapter(adapter);

        stopSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view,
                                       int position, long id) {
                selectedStopIndex = position;
                // Recalculate ETA to newly selected stop immediately
                if (lastLatLng != null) {
                    fetchETA(lastLatLng.latitude, lastLatLng.longitude);
                }
                // Move camera to show selected stop marker
                addStopMarker(position);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        mMap = googleMap;
        mMap.setMapType(GoogleMap.MAP_TYPE_NORMAL);
        mMap.getUiSettings().setZoomControlsEnabled(true);
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(
                new LatLng(GATE_LAT, GATE_LNG), 14));
        mapReady = true;

        // Load stop coordinates from server then start polling
        fetchStopCoords();
    }

    // ── Fetch stop lat/lng from /buses so we can ETA accurately ──
    private void fetchStopCoords() {
        new Thread(() -> {
            try {
                // Get today's schedule from server which includes stop lat/lng
                String day = getTodayString();
                URL url = new URL(Constants.BASE_URL + "/buses?day=" + day);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(6000);

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()));
                StringBuilder result = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) result.append(line);

                JSONArray array = new JSONArray(result.toString());
                for (int i = 0; i < array.length(); i++) {
                    JSONObject obj = array.getJSONObject(i);
                    if (obj.getString("busId").equals(busId)) {
                        JSONArray stops = obj.getJSONArray("stops");
                        stopCoords.clear();
                        for (int j = 0; j < stops.length(); j++) {
                            JSONObject s = stops.getJSONObject(j);
                            double sLat = s.optDouble("lat", GATE_LAT);
                            double sLng = s.optDouble("lng", GATE_LNG);
                            stopCoords.add(new double[]{sLat, sLng});
                        }
                        break;
                    }
                }
            } catch (Exception ignored) {}

            // Start polling bus location
            startPolling();
        }).start();
    }

    // ── Add pin for selected stop on map ──────────────────────────
    private void addStopMarker(int stopIndex) {
        if (!mapReady) return;
        if (stopIndex >= stopCoords.size()) return;

        double[] coord = stopCoords.get(stopIndex);
        LatLng stopPos = new LatLng(coord[0], coord[1]);
        String label   = (stopIndex < stopNames.size()) ? stopNames.get(stopIndex) : "Stop";

        mMap.addMarker(new MarkerOptions()
                .position(stopPos)
                .title(label)
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)));
    }

    // ── Poll bus location every 4 seconds ─────────────────────────
    private void startPolling() {
        new Thread(() -> {
            while (isRunning) {
                try {
                    URL url = new URL(Constants.BASE_URL + "/getLocation?busId=" + busId);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(6000);

                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream()));
                    StringBuilder result = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) result.append(line);

                    JSONObject obj = new JSONObject(result.toString());
                    boolean started = obj.getBoolean("started");

                    if (!started) {
                        runOnUiThread(this::showNotSharing);
                    } else {
                        double lat = obj.getDouble("lat");
                        double lng = obj.getDouble("lng");

                        // Load route history first time
                        if (routePoints.isEmpty()) {
                            JSONArray route = obj.optJSONArray("route");
                            if (route != null) {
                                for (int i = 0; i < route.length(); i++) {
                                    JSONObject pt = route.getJSONObject(i);
                                    routePoints.add(new LatLng(
                                            pt.getDouble("lat"), pt.getDouble("lng")));
                                }
                            }
                        }

                        routePoints.add(new LatLng(lat, lng));
                        if (routePoints.size() > 200) routePoints.remove(0);

                        runOnUiThread(() -> {
                            showTracking();
                            animateMarker(new LatLng(lat, lng));
                            drawPolyline();
                            fetchETA(lat, lng);
                        });
                    }

                    Thread.sleep(4000);
                } catch (Exception e) {
                    runOnUiThread(() -> showNotSharingMsg("❌ Could not connect to server."));
                    try { Thread.sleep(6000); } catch (InterruptedException ignored) {}
                }
            }
        }).start();
    }

    // ── ETA to currently selected stop ────────────────────────────
    private void fetchETA(double busLat, double busLng) {
        // Get selected stop coordinates
        double stopLat = GATE_LAT;
        double stopLng = GATE_LNG;
        String stopLabel = "IIT Ropar Gate";

        if (selectedStopIndex < stopCoords.size()) {
            stopLat  = stopCoords.get(selectedStopIndex)[0];
            stopLng  = stopCoords.get(selectedStopIndex)[1];
        }
        if (selectedStopIndex < stopNames.size()) {
            stopLabel = stopNames.get(selectedStopIndex);
        }

        final double finalStopLat = stopLat;
        final double finalStopLng = stopLng;
        final String finalLabel   = stopLabel;

        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("busLat",  busLat);
                body.put("busLng",  busLng);
                body.put("stopLat", finalStopLat);
                body.put("stopLng", finalStopLng);

                ApiClient.post(this, "/getETA", body, new ApiClient.Callback() {
                    @Override
                    public void onSuccess(JSONObject response) {
                        runOnUiThread(() -> {
                            try {
                                int etaMins = response.getInt("etaMinutes");
                                int distM   = response.getInt("distanceMeters");
                                if (tvEta != null) {
                                    if (distM < 80) {
                                        tvEta.setText("🏁 Bus arrived at " + finalLabel + "!");
                                    } else {
                                        tvEta.setText("🕒 " + finalLabel
                                                + " → ~" + etaMins + " min  |  "
                                                + distM + " m away");
                                    }
                                }
                            } catch (Exception ignored) {}
                        });
                    }
                    @Override public void onError(String msg) {}
                });
            } catch (Exception ignored) {}
        }).start();
    }

    // ── UI states ─────────────────────────────────────────────────
    private void showNotSharing() {
        showNotSharingMsg("🚌 " + (busTitle != null ? busTitle : "Bus")
                + "\n\nLocation not being shared yet."
                + "\nDeparture: " + (departure != null ? departure : "")
                + "\n\nCheck back closer to departure time.");
    }

    private void showNotSharingMsg(String msg) {
        if (layoutNotSharing != null) layoutNotSharing.setVisibility(View.VISIBLE);
        if (layoutTracking   != null) layoutTracking.setVisibility(View.GONE);
        if (tvNotSharingMsg  != null) tvNotSharingMsg.setText(msg);
    }

    private void showTracking() {
        if (layoutNotSharing != null) layoutNotSharing.setVisibility(View.GONE);
        if (layoutTracking   != null) layoutTracking.setVisibility(View.VISIBLE);
    }

    // ── Smooth marker animation ────────────────────────────────────
    private void animateMarker(LatLng newPos) {
        if (!mapReady) return;

        if (busMarker == null) {
            busMarker = mMap.addMarker(new MarkerOptions()
                    .position(newPos)
                    .title("🚌 " + (busTitle != null ? busTitle : "Bus"))
                    .icon(BitmapDescriptorFactory.defaultMarker(
                            BitmapDescriptorFactory.HUE_AZURE)));
            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(newPos, 16));
            lastLatLng = newPos;
            return;
        }

        if (lastLatLng == null) {
            busMarker.setPosition(newPos);
            lastLatLng = newPos;
            return;
        }

        LatLng from = lastLatLng;
        lastLatLng  = newPos;

        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(900);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(a -> {
            float  t   = (float) a.getAnimatedValue();
            double lat = from.latitude  + t * (newPos.latitude  - from.latitude);
            double lng = from.longitude + t * (newPos.longitude - from.longitude);
            if (busMarker != null) busMarker.setPosition(new LatLng(lat, lng));
        });
        anim.start();

        mMap.animateCamera(CameraUpdateFactory.newLatLng(newPos));
    }

    // ── Polyline ──────────────────────────────────────────────────
    private void drawPolyline() {
        if (!mapReady || routePoints.size() < 2) return;
        if (routePolyline == null) {
            routePolyline = mMap.addPolyline(new PolylineOptions()
                    .addAll(routePoints)
                    .width(8f)
                    .color(Color.parseColor("#42A5F5"))
                    .geodesic(true));
        } else {
            routePolyline.setPoints(routePoints);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────
    private String getTodayString() {
        int day = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK);
        if (day == java.util.Calendar.MONDAY)    return "Monday";
        if (day == java.util.Calendar.TUESDAY)   return "Tuesday";
        if (day == java.util.Calendar.WEDNESDAY) return "Wednesday";
        if (day == java.util.Calendar.THURSDAY)  return "Thursday";
        if (day == java.util.Calendar.FRIDAY)    return "Friday";
        if (day == java.util.Calendar.SATURDAY)  return "Saturday";
        return "Sunday";
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isRunning = false;
    }
}