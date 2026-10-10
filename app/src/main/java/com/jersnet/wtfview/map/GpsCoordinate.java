package com.jersnet.wtfview.map;

public class GpsCoordinate {
    public double latitude;
    public double longitude;
    public long timestamp;

    public GpsCoordinate(double latitude, double longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.timestamp = System.currentTimeMillis();
    }

    public GpsCoordinate(double latitude, double longitude, long timestamp) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.timestamp = timestamp;
    }

    public boolean isValid() {
        return latitude >= -90.0 && latitude <= 90.0 &&
               longitude >= -180.0 && longitude <= 180.0 &&
               !(Math.abs(latitude) < 0.000001 && Math.abs(longitude) < 0.000001);
    }
}
