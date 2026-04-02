package com.example.finalfinalproj;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.*;
import android.widget.*;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

public class BusAdapter extends RecyclerView.Adapter<BusAdapter.ViewHolder> {

    List<BusModel> list;
    Context        context;

    public BusAdapter(Context context, List<BusModel> list) {
        this.context = context;
        this.list    = list;
    }

    // ── ViewHolder ──────────────────────────────────────────────────────
    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView title, busId, departure, stopTime;

        public ViewHolder(View v) {
            super(v);
            title     = v.findViewById(R.id.title);
            busId     = v.findViewById(R.id.busId);
            departure = v.findViewById(R.id.departure);
            stopTime  = v.findViewById(R.id.stopTime);
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.bus_item, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        BusModel bus = list.get(position);

        holder.title.setText(bus.title);
        if (holder.busId != null) holder.busId.setText(bus.busId);
        holder.departure.setText("Departure: " + bus.departure);
        holder.stopTime.setText(bus.stopName + ": " + bus.stopTime);

        // Click → fetch this bus's live location using its busId
        holder.itemView.setOnClickListener(v -> fetchAndOpenMap(bus));
    }

    @Override
    public int getItemCount() { return list.size(); }

    // ── Fetch location for a specific bus ───────────────────────────────
    private void fetchAndOpenMap(BusModel bus) {
        new Thread(() -> {
            try {
                URL url = new URL(Constants.BASE_URL + "/getLocation?busId=" + bus.busId);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()));
                StringBuilder result = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) result.append(line);

                JSONObject obj = new JSONObject(result.toString());

                ((Activity) context).runOnUiThread(() -> {
                    try {
                        boolean started = obj.getBoolean("started");
                        if (!started) {
                            Toast.makeText(context,
                                    bus.title + " has not started yet 🚫",
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            Intent intent = new Intent(context, MapActivity.class);
                            intent.putExtra("lat",   obj.getDouble("lat"));
                            intent.putExtra("lng",   obj.getDouble("lng"));
                            intent.putExtra("busId", bus.busId);
                            intent.putExtra("title", bus.title);
                            context.startActivity(intent);
                        }
                    } catch (Exception e) {
                        Toast.makeText(context, "Parse error", Toast.LENGTH_SHORT).show();
                    }
                });

            } catch (Exception e) {
                ((Activity) context).runOnUiThread(() ->
                        Toast.makeText(context, "Server Error ❌", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }
}