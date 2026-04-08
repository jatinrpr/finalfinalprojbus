package com.example.finalfinalproj;

import android.content.Context;
import android.content.Intent;
import android.view.*;
import android.widget.*;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Calendar;
import java.util.List;

public class BusAdapter extends RecyclerView.Adapter<BusAdapter.ViewHolder> {

    List<BusModel> list;
    Context        context;

    public BusAdapter(Context context, List<BusModel> list) {
        this.context = context;
        this.list    = list;
    }

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
        holder.departure.setText("🕐 Departure: " + bus.departure);

        // Show Bella Chowk status if this bus has that stop
        String bellaStatus = getBellaChowkStatus(bus);
        String stopLine = "📍 " + bus.stopName + "  " + bus.stopTime;
        if (bellaStatus != null) stopLine += "\n" + bellaStatus;
        holder.stopTime.setText(stopLine);

        // Tap → open MapActivity with all stop data
        holder.itemView.setOnClickListener(v -> {
            Intent intent = new Intent(context, MapActivity.class);
            intent.putExtra("busId",     bus.busId);
            intent.putExtra("title",     bus.title);
            intent.putExtra("departure", bus.departure);

            // Pass stops as arrays
            intent.putStringArrayListExtra("stopNames", bus.stopNames);
            intent.putStringArrayListExtra("stopTimes", bus.stopTimes);

            context.startActivity(intent);
        });
    }

    @Override
    public int getItemCount() { return list.size(); }

    // ── Bella Chowk status helper ─────────────────────────────────────
    /**
     * Returns "🚌 On the way to Bella Chowk" or "🔄 Returning from Bella Chowk"
     * if this bus has a Bella Chowk stop, or null if it doesn't.
     */
    private static String getBellaChowkStatus(BusModel bus) {
        for (int i = 0; i < bus.stopNames.size(); i++) {
            String name = bus.stopNames.get(i).toLowerCase().trim();
            if (name.contains("bella") &&
                    (name.contains("chowk") || name.contains("chauk") || name.contains("chawk"))) {
                if (i >= bus.stopTimes.size()) break;
                try {
                    int stopMin = parseTimeToMinutes(bus.stopTimes.get(i));
                    Calendar now = Calendar.getInstance();
                    int nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
                    return (nowMin < stopMin)
                            ? "🚌 On the way to Bella Chowk"
                            : "🔄 Returning from Bella Chowk";
                } catch (Exception ignored) {}
                break;
            }
        }
        return null;
    }

    private static int parseTimeToMinutes(String timeStr) {
        timeStr = timeStr.trim().toUpperCase();
        boolean isPM = timeStr.contains("PM");
        boolean isAM = timeStr.contains("AM");
        timeStr = timeStr.replace("AM", "").replace("PM", "").trim();
        String[] parts = timeStr.split(":");
        int hour = Integer.parseInt(parts[0].trim());
        int min  = Integer.parseInt(parts[1].trim());
        if (isPM && hour != 12) hour += 12;
        if (isAM && hour == 12) hour = 0;
        return hour * 60 + min;
    }
}