package com.datacollector;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
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

    // 6 collection steps + 8 sync steps = 14 total
    private static final int COLLECT_STEPS = 6;
    private static final int TOTAL_STEPS   = COLLECT_STEPS + DataSyncManager.SYNC_TABLE_COUNT;

    private TextView   statusText;
    private TextView   progressPercent;
    private ProgressBar progressBar;
    private int        stepsCompleted = 0;

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
        statusText      = findViewById(R.id.statusText);
        progressPercent = findViewById(R.id.progressPercent);
        progressBar     = findViewById(R.id.progressBar);
        stepsCompleted  = 0;
        updateProgress(0, "Starting…");

        // Auto-start: request permissions or immediately collect+sync
        if (allPermissionsGranted()) {
            startCollectionAndSync();
        } else {
            statusText.setText("Requesting permissions…");
            requestRequiredPermissions();
        }
    }

    /** Update progress bar and label on the UI thread */
    private void updateProgress(int steps, String label) {
        int pct = (int) ((steps / (float) TOTAL_STEPS) * 100);
        runOnUiThread(() -> {
            progressBar.setProgress(pct);
            progressPercent.setText(pct + "%");
            statusText.setText(label);
        });
    }

    private void startCollectionAndSync() {
        stepsCompleted = 0;

        new Thread(() -> {
            // --- Collection phase (6 steps) ---
            updateProgress(0, "Collecting SMS…");
            new SmsCollector(this).collect();
            updateProgress(++stepsCompleted, "Collecting location…");

            new LocationCollector(this).collect();
            updateProgress(++stepsCompleted, "Checking SIM…");

            new SimChangeDetector(this).checkAndRecordSimChange();
            updateProgress(++stepsCompleted, "Analysing SMS data…");

            new SmsAnalyzer(this).analyze();
            updateProgress(++stepsCompleted, "Collecting device info…");

            new DeviceInfoCollector(this).collect();
            updateProgress(++stepsCompleted, "Collecting installed apps…");

            new InstalledAppsCollector(this).collect();
            updateProgress(++stepsCompleted, "Syncing to server…");

            // --- Sync phase (8 steps via ProgressCallback) ---
            String[] tableLabels = {
                "Syncing SMS…",
                "Syncing location…",
                "Syncing SIM history…",
                "Syncing mobile money…",
                "Syncing telecom usage…",
                "Syncing ride-hailing…",
                "Syncing device info…",
                "Syncing installed apps…",
            };

            new DataSyncManager(this).syncAll(
                // onComplete
                () -> runOnUiThread(() -> {
                    progressBar.setProgress(100);
                    progressPercent.setText("100%");
                    statusText.setText("✅ All data synced!");
                    Toast.makeText(this, "Sync complete!", Toast.LENGTH_SHORT).show();
                }),
                // onProgress — fires after each table
                (done, total) -> {
                    String label = (done - 1 < tableLabels.length)
                        ? tableLabels[done - 1] : "Syncing…";
                    updateProgress(COLLECT_STEPS + done, label);
                }
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
