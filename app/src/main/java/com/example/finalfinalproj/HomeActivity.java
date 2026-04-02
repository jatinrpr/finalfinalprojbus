package com.example.finalfinalproj;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.*;

import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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
 * Home screen for students.
 * Shows: welcome name, day-filtered bus schedule, live map (first active bus).
 */
public class HomeActivity extends FragmentActivity implements OnMapReadyCallback {

    private GoogleMap  mMap;
    private Marker     busMarker;

    private RecyclerView  recyclerView;
    private Spinner       daySpinner;
    private BusAdapter    adapter;
    private List<BusModel> list = new ArrayList<>();

    private volatile boolean isRunning = true;

    // Day labels → API query strings
    private static final String[] DAY_LABELS = {"Mon-Thu", "Friday", "Saturday", "Sunday"};
    private static final String[] DAY_API    = {"Monday",  "Friday", "Saturday", "Sunday"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        // ── Welcome ───────────────────────────────────────────────────
        TextView tvWelcome = findViewById(R.id.tvWelcome);
        SharedPreferences prefs = getSharedPreferences(Constants.PREFS, MODE_PRIVATE);
        String name = prefs.getString(Constants.KEY_NAME, "Student");
        tvWelcome.setText("Welcome, " + name + " 👋");

        // ── Logout ────────────────────────────────────────────────────
        Button btnLogout = findViewById(R.id.btnLogout);
        btnLogout.setOnClickListener(v -> {
            getSharedPreferences(Constants.PREFS, MODE_PRIVATE).edit().clear().apply();
            startActivity(new Intent(HomeActivity.this, MainActivity.class));
            finish();
        });

        // ── Day Spinner ───────────────────────────────────────────────
        daySpinner = findViewById(R.id.daySpinner);
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, DAY_LABELS);
        daySpinner.setAdapter(spinnerAdapter);

        // Auto-select today
        Calendar cal = Calendar.getInstance();
        int today = cal.get(Calendar.DAY_OF_WEEK);
        int initialPos = 0;
        if      (today == Calendar.FRIDAY)   initialPos = 1;
        else if (today == Calendar.SATURDAY) initialPos = 2;
        else if (today == Calendar.SUNDAY)   initialPos = 3;
        daySpinner.setSelection(initialPos);

        daySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, android.view.View view,
                                       int position, long id) {
                loadBusData(DAY_API[position]);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        // ── Bus RecyclerView ──────────────────────────────────────────
        recyclerView = findViewById(R.id.busRecycler);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new BusAdapter(this, list);
        recyclerView.setAdapter(adapter);

        // Load today's schedule
        loadBusData(DAY_API[initialPos]);

        // ── Map ───────────────────────────────────────────────────────
        SupportMapFragment mapFragment =
                (SupportMapFragment) getSupportFragmentManager()
                        .findFragmentById(R.id.map);
        if (mapFragment != null) mapFragment.getMapAsync(this);
    }

    // ── Load schedule from API ────────────────────────────────────────────
    private void loadBusData(String day) {
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

                List<BusModel> newList = new ArrayList<>();
                for (int i = 0; i < array.length(); i++) {
                    JSONObject obj = array.getJSONObject(i);
                    String busId     = obj.getString("busId");
                    String title     = obj.getString("title");
                    String departure = obj.getString("departure");
                    JSONArray stops  = obj.getJSONArray("stops");
                    String stopName  = stops.getJSONObject(0).getString("name");
                    String stopTime  = stops.getJSONObject(0).getString("time");
                    newList.add(new BusModel(busId, title, departure, stopName, stopTime));
                }

                runOnUiThread(() -> {
                    list.clear();
                    list.addAll(newList);
                    adapter.notifyDataSetChanged();
                });

            } catch (Exception e) {
                // Fallback: load hardcoded data if server unreachable
                runOnUiThread(() -> {
                    list.clear();
                    list.addAll(getHardcodedSchedule(day));
                    adapter.notifyDataSetChanged();
                });
            }
        }).start();
    }

    // ── MAP callbacks ─────────────────────────────────────────────────────
    @Override
    public void onMapReady(GoogleMap googleMap) {
        mMap = googleMap;
        LatLng iit = new LatLng(30.9687, 76.4737);
        busMarker = mMap.addMarker(new MarkerOptions().position(iit).title("Bus"));
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(iit, 16));

        // Continuously poll the first bus that is active
        startMapPolling();
    }

    private void startMapPolling() {
        new Thread(() -> {
            while (isRunning) {
                try {
                    // Try each bus in the current list and show the first active one
                    List<BusModel> snapshot = new ArrayList<>(list);
                    boolean found = false;

                    for (BusModel bus : snapshot) {
                        URL url = new URL(Constants.BASE_URL + "/getLocation?busId=" + bus.busId);
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setRequestMethod("GET");

                        BufferedReader reader = new BufferedReader(
                                new InputStreamReader(conn.getInputStream()));
                        StringBuilder result = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) result.append(line);

                        JSONObject obj = new JSONObject(result.toString());
                        if (obj.getBoolean("started")) {
                            double lat = obj.getDouble("lat");
                            double lng = obj.getDouble("lng");
                            runOnUiThread(() -> updateMap(lat, lng));
                            found = true;
                            break;
                        }
                    }

                    Thread.sleep(4000);
                } catch (Exception e) {
                    try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
                }
            }
        }).start();
    }

    private void updateMap(double lat, double lng) {
        LatLng loc = new LatLng(lat, lng);
        if (busMarker == null) {
            busMarker = mMap.addMarker(new MarkerOptions().position(loc).title("Bus"));
        } else {
            busMarker.setPosition(loc);
        }
        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(loc, 16));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isRunning = false;
    }

    // ── Hardcoded fallback schedule ───────────────────────────────────────
    private List<BusModel> getHardcodedSchedule(String day) {
        List<BusModel> buses = new ArrayList<>();
        switch (day) {
            case "Monday":
            case "Tuesday":
            case "Wednesday":
            case "Thursday":
                buses.add(new BusModel("BUS001","Local Round Trip","08:15 AM","Railway Station","08:30 AM"));
                buses.add(new BusModel("BUS002","Local Round Trip","09:05 AM","Bela Chowk","09:37 AM"));
                buses.add(new BusModel("BUS003","Local Trip to Bela Chowk","03:10 PM","Bela Chowk","03:25 PM"));
                buses.add(new BusModel("BUS004","Local Trip to Bela Chowk","05:00 PM","Bela Chowk","05:15 PM"));
                buses.add(new BusModel("BUS005","Bus Stand & Police Lines","05:50 PM","Railway Station","06:05 PM"));
                buses.add(new BusModel("BUS006","Local Round Trip","07:00 PM","Bela Chowk","07:30 PM"));
                break;
            case "Friday":
                buses.add(new BusModel("BUS007","Bus Stand Trip","08:15 AM","Railway Station","08:30 AM"));
                buses.add(new BusModel("BUS008","Bela Chowk Trip","09:05 AM","Bela Chowk","09:37 AM"));
                buses.add(new BusModel("BUS009","Bela Chowk Trip","03:10 PM","Bela Chowk","03:25 PM"));
                buses.add(new BusModel("BUS010","Bela Chowk Trip","05:00 PM","Bela Chowk","05:15 PM"));
                buses.add(new BusModel("BUS011","Local Round Trip","05:50 PM","Railway Station","06:05 PM"));
                buses.add(new BusModel("BUS012","Bela Chowk Trip","06:15 PM","Bela Chowk","06:29 PM"));
                buses.add(new BusModel("BUS013","Local Round Trip","07:10 PM","Bela Chowk","07:40 PM"));
                break;
            case "Saturday":
                buses.add(new BusModel("BUS014","Local Round Trip","08:30 AM","Railway Station","08:45 AM"));
                buses.add(new BusModel("BUS015","Bela Chowk Trip","10:30 AM","Bela Chowk","10:45 AM"));
                buses.add(new BusModel("BUS016","Bela Chowk Trip","11:00 AM","Bela Chowk","11:15 AM"));
                buses.add(new BusModel("BUS017","Bela Chowk Trip","03:00 PM","Bela Chowk","03:15 PM"));
                buses.add(new BusModel("BUS018","Bela Chowk Trip","04:00 PM","Bela Chowk","04:15 PM"));
                buses.add(new BusModel("BUS019","Local Round Trip","05:30 PM","Bela Chowk","05:45 PM"));
                buses.add(new BusModel("BUS020","Local Round Trip","06:40 PM","Railway Station","06:55 PM"));
                break;
            case "Sunday":
                buses.add(new BusModel("BUS021","Bela Chowk Trip","10:00 AM","Bela Chowk","10:15 AM"));
                buses.add(new BusModel("BUS022","Bela Chowk Trip","11:00 AM","Bela Chowk","11:15 AM"));
                buses.add(new BusModel("BUS023","Bela Chowk Trip","03:30 PM","Bela Chowk","03:45 PM"));
                buses.add(new BusModel("BUS024","Bela Chowk Trip","04:15 PM","Bela Chowk","04:31 PM"));
                break;
        }
        return buses;
    }
}
