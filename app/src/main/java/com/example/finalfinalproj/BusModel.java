package com.example.finalfinalproj;

public class BusModel {

    public String busId;       // unique ID e.g. "BUS001"
    public String title;
    public String departure;
    public String stopName;
    public String stopTime;

    public BusModel(String busId, String title, String departure,
                    String stopName, String stopTime) {
        this.busId     = busId;
        this.title     = title;
        this.departure = departure;
        this.stopName  = stopName;
        this.stopTime  = stopTime;
    }
}
