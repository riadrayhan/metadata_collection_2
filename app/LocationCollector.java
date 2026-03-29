package com.datacollector;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.os.Looper;
import com.google.android.gms.location.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class LocationCollector {

    private final Context context;
    private final DatabaseHelper db;
    private final FusedLocationProviderClient fusedClient;

    public LocationCollector(Context context) {
        this.context = context;
        this.db = DatabaseHelper.getInstance(context);
        this.fusedClient = LocationServices.getFusedLocationProviderClient(context);
    }

    public void collect() {
        try {
            // Try last known location first
            fusedClient.getLastLocation().addOnSuccessListener(location -> {
                if (location != null) {
                    saveLocation(location);
                } else {
                    // Request a fresh location if last known is null
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
                        saveLocation(result.getLastLocation());
                    }
                    fusedClient.removeLocationUpdates(this);
                }
            }, Looper.getMainLooper());
        } catch (SecurityException e) {
            e.printStackTrace();
        }
    }

    private void saveLocation(Location location) {
        String timestamp = new SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss", Locale.getDefault()
        ).format(new Date(location.getTime()));

        // Reverse geocode to get address/place name
        String addressStr = "";
        try {
            Geocoder geocoder = new Geocoder(context, Locale.getDefault());
            List<Address> addresses = geocoder.getFromLocation(
                location.getLatitude(), location.getLongitude(), 1);
            if (addresses != null && !addresses.isEmpty()) {
                Address addr = addresses.get(0);
                StringBuilder sb = new StringBuilder();
                // Build readable address
                if (addr.getSubLocality() != null) sb.append(addr.getSubLocality()).append(", ");
                if (addr.getLocality() != null) sb.append(addr.getLocality()).append(", ");
                if (addr.getSubAdminArea() != null) sb.append(addr.getSubAdminArea()).append(", ");
                if (addr.getAdminArea() != null) sb.append(addr.getAdminArea());
                addressStr = sb.toString();
                if (addressStr.endsWith(", ")) {
                    addressStr = addressStr.substring(0, addressStr.length() - 2);
                }
                // If still empty, try full address line
                if (addressStr.isEmpty() && addr.getMaxAddressLineIndex() >= 0) {
                    addressStr = addr.getAddressLine(0);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        db.insertLocation(
            location.getLatitude(),
            location.getLongitude(),
            location.getAccuracy(),
            timestamp,
            addressStr
        );
    }
}
