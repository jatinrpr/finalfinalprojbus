package com.example.finalfinalproj;

import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

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

public class BusListActivity extends AppCompatActivity {

    RecyclerView  recyclerView;
    List<BusModel> list;
    BusAdapter    adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bus_list);

        recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        list    = new ArrayList<>();
        adapter = new BusAdapter(this, list);
        recyclerView.setAdapter(adapter);

        loadBuses();
    }

    private void loadBuses() {
        Calendar calendar = Calendar.getInstance();
        int day = calendar.get(Calendar.DAY_OF_WEEK);

        String today;
        if      (day == Calendar.MONDAY)    today = "Monday";
        else if (day == Calendar.TUESDAY)   today = "Tuesday";
        else if (day == Calendar.WEDNESDAY) today = "Wednesday";
        else if (day == Calendar.THURSDAY)  today = "Thursday";
        else if (day == Calendar.FRIDAY)    today = "Friday";
        else if (day == Calendar.SATURDAY)  today = "Saturday";
        else                                today = "Sunday";

        Log.d("BUS_DAY", today);

        String finalDay = today;
        new Thread(() -> {
            try {
                URL url = new URL(Constants.BASE_URL + "/buses?day=" + finalDay);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()));
                StringBuilder result = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) result.append(line);

                Log.d("BUS_API", result.toString());

                JSONArray array = new JSONArray(result.toString());
                list.clear();

                for (int i = 0; i < array.length(); i++) {
                    JSONObject obj = array.getJSONObject(i);

                    String busId     = obj.getString("busId");
                    String title     = obj.getString("title");
                    String departure = obj.getString("departure");

                    JSONArray stops   = obj.getJSONArray("stops");
                    String stopName   = stops.getJSONObject(0).getString("name");
                    String stopTime   = stops.getJSONObject(0).getString("time");

                    list.add(new BusModel(busId, title, departure, stopName, stopTime));
                }

                runOnUiThread(() -> adapter.notifyDataSetChanged());

            } catch (Exception e) {
                Log.e("BUS_LIST", e.getMessage(), e);
                runOnUiThread(() ->
                        Toast.makeText(this, "Could not load buses ❌", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }
}
