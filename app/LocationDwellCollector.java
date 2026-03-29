package com.datacollector;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.os.Looper;
import com.google.android.gms.location.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Enhanced location tracker that records dwell time (how long user stays at a location).
 * Records location with intervals and computes cumulative dwell time per place.
 */
public class LocationDwellCollector {

    private final Context context;
    private final DatabaseHelper db;
    private final FusedLocationProviderClient fusedClient;
    private static final float DWELL_RADIUS_METERS = 100f; // Same location threshold
    private static final String PREFS_NAME = "location_dwell_prefs";

    public LocationDwellCollector(Context context) {
        this.context = context;
        this.db = DatabaseHelper.getInstance(context);
        this.fusedClient = LocationServices.getFusedLocationProviderClient(context);
    }

    public void collect() {
        try {
            fusedClient.getLastLocation().addOnSuccessListener(location -> {
                if (location != null) {
                    processLocation(location);
                } else {
                    requestFreshLocation();
                }
            });
        } catch (SecurityException e) {
            e.printStackTrace();
        }
    }

    private void requestFreshLocation() {
        try {
            LocationRequest request = new LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY, 5000)
                .setMaxUpdates(1)
                .build();

            fusedClient.requestLocationUpdates(request, new LocationCallback() {
                @Override
                public void onLocationResult(LocationResult result) {
                    if (result != null && result.getLastLocation() != null) {
                        processLocation(result.getLastLocation());
                    }
                    fusedClient.removeLocationUpdates(this);
                }
            }, Looper.getMainLooper());
        } catch (SecurityException e) {
            e.printStackTrace();
        }
    }

    private void processLocation(Location location) {
        String timestamp = new SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(location.getTime()));

        double lat = location.getLatitude();
        double lng = location.getLongitude();
        float accuracy = location.getAccuracy();

        // Reverse geocode
        String addressStr = reverseGeocode(lat, lng);

        // Calculate dwell time by checking last recorded dwell location
        android.content.SharedPreferences prefs =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        double lastLat = Double.longBitsToDouble(prefs.getLong("last_lat", 0));
        double lastLng = Double.longBitsToDouble(prefs.getLong("last_lng", 0));
        long lastTimestamp = prefs.getLong("last_timestamp", 0);

        long now = System.currentTimeMillis();
        long dwellMinutes = 0;
        boolean sameLocation = false;

        if (lastTimestamp > 0) {
            float[] results = new float[1];
            Location.distanceBetween(lastLat, lastLng, lat, lng, results);
            if (results[0] < DWELL_RADIUS_METERS) {
                // Still at same location
                dwellMinutes = (now - lastTimestamp) / (1000 * 60);
                sameLocation = true;
            }
        }

        // Determine location type based on time of day and day of week
        String locationType = classifyLocation(timestamp);

        // Determine if this is a frequent location
        int visitCount = db.getLocationVisitCount(lat, lng, DWELL_RADIUS_METERS);

        db.insertLocationDwell(
            lat, lng, accuracy, addressStr, timestamp,
            dwellMinutes, locationType, visitCount + 1,
            sameLocation ? "DWELL" : "ARRIVAL"
        );

        // Update last location
        prefs.edit()
            .putLong("last_lat", Double.doubleToLongBits(lat))
            .putLong("last_lng", Double.doubleToLongBits(lng))
            .putLong("last_timestamp", sameLocation ? lastTimestamp : now)
            .apply();
    }

    private String classifyLocation(String timestamp) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            Date date = sdf.parse(timestamp);
            if (date == null) return "UNKNOWN";

            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.setTime(date);
            int hour = cal.get(java.util.Calendar.HOUR_OF_DAY);
            int dayOfWeek = cal.get(java.util.Calendar.DAY_OF_WEEK);

            boolean isWeekend = (dayOfWeek == java.util.Calendar.SATURDAY
                || dayOfWeek == java.util.Calendar.FRIDAY); // BD weekend

            if (hour >= 22 || hour < 6)     return "NIGHT_HOME";
            if (hour >= 9 && hour < 17 && !isWeekend) return "WORK_HOURS";
            if (isWeekend)                   return "WEEKEND";
            if (hour >= 6 && hour < 9)       return "MORNING_COMMUTE";
            if (hour >= 17 && hour < 22)     return "EVENING";
            return "OTHER";
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    private String reverseGeocode(double lat, double lng) {
        try {
            Geocoder geocoder = new Geocoder(context, Locale.getDefault());
            List<Address> addresses = geocoder.getFromLocation(lat, lng, 1);
            if (addresses != null && !addresses.isEmpty()) {
                Address addr = addresses.get(0);
                StringBuilder sb = new StringBuilder();
                if (addr.getSubLocality() != null) sb.append(addr.getSubLocality()).append(", ");
                if (addr.getLocality() != null) sb.append(addr.getLocality()).append(", ");
                if (addr.getSubAdminArea() != null) sb.append(addr.getSubAdminArea()).append(", ");
                if (addr.getAdminArea() != null) sb.append(addr.getAdminArea());
                String result = sb.toString();
                if (result.endsWith(", ")) result = result.substring(0, result.length() - 2);
                if (result.isEmpty() && addr.getMaxAddressLineIndex() >= 0) {
                    result = addr.getAddressLine(0);
                }
                return result;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "";
    }
}
