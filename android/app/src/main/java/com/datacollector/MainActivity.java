package com.datacollector;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private TextView statusText;

    private final String[] REQUIRED_PERMISSIONS = {
        Manifest.permission.READ_SMS,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.INTERNET
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Safety guard: if T&C not accepted, redirect back to TermsActivity
        SharedPreferences prefs = getSharedPreferences(TermsActivity.PREFS_NAME, MODE_PRIVATE);
        if (!prefs.getBoolean(TermsActivity.KEY_AGREED, false)) {
            startActivity(new Intent(this, TermsActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        Button btnCollect = findViewById(R.id.btnCollect);
        Button btnSync = findViewById(R.id.btnSync);

        btnCollect.setOnClickListener(v -> {
            if (allPermissionsGranted()) {
                startCollection();
            } else {
                requestPermissions();
            }
        });

        btnSync.setOnClickListener(v -> {
            DataSyncManager syncManager = new DataSyncManager(this);
            syncManager.syncAll(() -> {
                runOnUiThread(() -> Toast.makeText(this, "Sync complete!", Toast.LENGTH_SHORT).show());
            });
        });

        // Auto check SIM change on every launch
        if (allPermissionsGranted()) {
            SimChangeDetector simDetector = new SimChangeDetector(this);
            simDetector.checkAndRecordSimChange();
        }
    }

    private void startCollection() {
        statusText.setText("Collecting data...");

        new Thread(() -> {
            SmsCollector smsCollector = new SmsCollector(this);
            smsCollector.collect();

            LocationCollector locationCollector = new LocationCollector(this);
            locationCollector.collect();

            SimChangeDetector simDetector = new SimChangeDetector(this);
            simDetector.checkAndRecordSimChange();

            // New collectors — SMS-parsed financial/telecom/ride data
            SmsAnalyzer smsAnalyzer = new SmsAnalyzer(this);
            smsAnalyzer.analyze();

            // Device info (root, age, factory reset, etc.)
            DeviceInfoCollector deviceCollector = new DeviceInfoCollector(this);
            deviceCollector.collect();

            // Installed apps detection
            InstalledAppsCollector appsCollector = new InstalledAppsCollector(this);
            appsCollector.collect();

            runOnUiThread(() -> statusText.setText("✅ All data collected! Press Sync to upload."));
        }).start();
    }

    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void requestPermissions() {
        List<String> needed = new ArrayList<>();
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(permission);
            }
        }
        ActivityCompat.requestPermissions(this,
            needed.toArray(new String[0]), PERMISSION_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
            String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (allPermissionsGranted()) {
                startCollection();
            } else {
                Toast.makeText(this, "All permissions required!", Toast.LENGTH_LONG).show();
            }
        }
    }
}
