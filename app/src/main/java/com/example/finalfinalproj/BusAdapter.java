package com.example.finalfinalproj;

import android.content.Context;
import android.content.Intent;
import android.view.*;
import android.widget.*;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

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
        holder.stopTime.setText("📍 " + bus.stopName + "  " + bus.stopTime);

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
}