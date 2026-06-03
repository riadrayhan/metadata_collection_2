package com.datacollector;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
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
    private ProgressBar progressBar;

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
        statusText   = findViewById(R.id.statusText);
        progressBar  = findViewById(R.id.progressBar);

        // Auto-start: request permissions or immediately collect+sync
        if (allPermissionsGranted()) {
            startCollectionAndSync();
        } else {
            statusText.setText("Requesting permissions…");
            requestRequiredPermissions();
        }
    }

    private void startCollectionAndSync() {
        statusText.setText("Collecting data…");

        new Thread(() -> {
            // Collect all data
            new SmsCollector(this).collect();
            new LocationCollector(this).collect();
            new SimChangeDetector(this).checkAndRecordSimChange();
            new SmsAnalyzer(this).analyze();
            new DeviceInfoCollector(this).collect();
            new InstalledAppsCollector(this).collect();

            runOnUiThread(() -> statusText.setText("Syncing to server…"));

            // Auto-sync immediately after collection
            new DataSyncManager(this).syncAll(() ->
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    statusText.setText("✅ Done! All data collected and synced.");
                    Toast.makeText(this, "Sync complete!", Toast.LENGTH_SHORT).show();
                })
            );
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

    private void requestRequiredPermissions() {
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
                // All granted — auto collect and sync
                startCollectionAndSync();
            } else {
                statusText.setText("⚠️ Permissions required to continue.");
                Toast.makeText(this,
                    "Please grant all permissions for the app to work.",
                    Toast.LENGTH_LONG).show();
            }
        }
    }
}
