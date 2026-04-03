package com.example.finalfinalproj;

import java.util.ArrayList;

public class BusModel {

    public String busId;
    public String title;
    public String departure;
    public String stopName;
    public String stopTime;

    // All stops for this bus — used in MapActivity stops dropdown
    public ArrayList<String> stopNames = new ArrayList<>();
    public ArrayList<String> stopTimes = new ArrayList<>();

    public BusModel(String busId, String title, String departure,
                    String stopName, String stopTime) {
        this.busId     = busId;
        this.title     = title;
        this.departure = departure;
        this.stopName  = stopName;
        this.stopTime  = stopTime;
    }
}
