package com.example.finalfinalproj;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.*;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;

public class HomeActivity extends AppCompatActivity {

    private RecyclerView   recyclerView;
    private Spinner        daySpinner;
    private BusAdapter     adapter;
    private ProgressBar    progressBar;
    private TextView       tvEmpty;
    private final List<BusModel> list = new ArrayList<>();

    private static final String[] DAY_LABELS = {"Mon-Thu", "Friday", "Saturday", "Sunday"};
    private static final String[] DAY_API    = {"Monday",  "Friday", "Saturday", "Sunday"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        // ── Welcome ───────────────────────────────────────────────
        TextView tvWelcome = findViewById(R.id.tvWelcome);
        SharedPreferences prefs = getSharedPreferences(Constants.PREFS, MODE_PRIVATE);
        String name = prefs.getString(Constants.KEY_NAME, "Student");
        tvWelcome.setText("Welcome, " + name + " 👋");

        // ── Logout ────────────────────────────────────────────────
        findViewById(R.id.btnLogout).setOnClickListener(v -> {
            prefs.edit().clear().apply();
            startActivity(new Intent(this, MainActivity.class));
            finish();
        });

        // ── Views ─────────────────────────────────────────────────
        progressBar  = findViewById(R.id.progressBar);
        tvEmpty      = findViewById(R.id.tvEmpty);
        recyclerView = findViewById(R.id.busRecycler);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new BusAdapter(this, list);
        recyclerView.setAdapter(adapter);

        // ── Day Spinner ───────────────────────────────────────────
        daySpinner = findViewById(R.id.daySpinner);
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                DAY_LABELS);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        daySpinner.setAdapter(spinnerAdapter);

        // Auto-select today
        int initialPos = getTodayPosition();
        daySpinner.setSelection(initialPos);

        daySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view,
                                       int position, long id) {
                loadBusData(DAY_API[position]);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        loadBusData(DAY_API[initialPos]);
    }

    // ── Detect today ──────────────────────────────────────────────
    private int getTodayPosition() {
        int day = Calendar.getInstance().get(Calendar.DAY_OF_WEEK);
        if (day == Calendar.FRIDAY)   return 1;
        if (day == Calendar.SATURDAY) return 2;
        if (day == Calendar.SUNDAY)   return 3;
        return 0;
    }

    // ── Load from MongoDB ─────────────────────────────────────────
    private void loadBusData(String day) {
        setLoading(true);

        new Thread(() -> {
            try {
                URL url = new URL(Constants.BASE_URL + "/buses?day=" + day);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);

                if (conn.getResponseCode() != 200)
                    throw new Exception("Server error " + conn.getResponseCode());

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()));
                StringBuilder result = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) result.append(line);

                JSONArray array = new JSONArray(result.toString());
                List<BusModel> newList = new ArrayList<>();

                for (int i = 0; i < array.length(); i++) {
                    JSONObject obj   = array.getJSONObject(i);
                    String busId     = obj.getString("busId");
                    String title     = obj.getString("title");
                    String departure = obj.getString("departure");

                    // Pass ALL stops to BusModel so MapActivity can show them
                    JSONArray stopsArr = obj.getJSONArray("stops");
                    ArrayList<String> stopNames = new ArrayList<>();
                    ArrayList<String> stopTimes = new ArrayList<>();
                    for (int j = 0; j < stopsArr.length(); j++) {
                        stopNames.add(stopsArr.getJSONObject(j).getString("name"));
                        stopTimes.add(stopsArr.getJSONObject(j).getString("time"));
                    }

                    String firstStop = stopNames.isEmpty() ? "" : stopNames.get(0);
                    String firstTime = stopTimes.isEmpty() ? "" : stopTimes.get(0);

                    BusModel model = new BusModel(busId, title, departure, firstStop, firstTime);
                    model.stopNames = stopNames;
                    model.stopTimes = stopTimes;
                    newList.add(model);
                }

                runOnUiThread(() -> {
                    setLoading(false);
                    list.clear();
                    list.addAll(newList);
                    adapter.notifyDataSetChanged();
                    if (tvEmpty != null)
                        tvEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    setLoading(false);
                    if (tvEmpty != null) {
                        tvEmpty.setText("❌ Could not load buses.\nCheck server is running.");
                        tvEmpty.setVisibility(View.VISIBLE);
                    }
                    Toast.makeText(this, "Server error: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void setLoading(boolean loading) {
        if (progressBar != null)
            progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (loading && tvEmpty != null)
            tvEmpty.setVisibility(View.GONE);
    }
}