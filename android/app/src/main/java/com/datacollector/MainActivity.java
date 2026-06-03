package com.datacollector;

import android.Manifest;
import android.app.AlertDialog;
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

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;

    // 6 collection steps + 8 sync steps = 14 total
    private static final int COLLECT_STEPS = 6;
    private static final int TOTAL_STEPS   = COLLECT_STEPS + DataSyncManager.SYNC_TABLE_COUNT;

    private TextView    statusText;
    private TextView    progressPercent;
    private ProgressBar progressBar;
    private int         stepsCompleted = 0;
    private int         currentPermIndex = 0;

    // Each permission with its title and explanation shown to the user
    private static final String[][] PERM_INFO = {
        {
            Manifest.permission.READ_SMS,
            "SMS Messages",
            "This app needs to read your SMS messages.\n\nPurpose: Extract financial transactions from bKash, Nagad, Rocket, telecom recharges, and ride-hailing (Uber/Pathao) SMS messages."
        },
        {
            Manifest.permission.ACCESS_FINE_LOCATION,
            "Precise Location (GPS)",
            "This app needs access to your precise GPS location.\n\nPurpose: Collect your current location coordinates for geographical activity analysis."
        },
        {
            Manifest.permission.ACCESS_COARSE_LOCATION,
            "Approximate Location (Network)",
            "This app needs access to your approximate network-based location.\n\nPurpose: Used as a fallback when GPS is unavailable to estimate your general location."
        },
        {
            Manifest.permission.READ_PHONE_STATE,
            "Phone & SIM Information",
            "This app needs to read your phone and SIM card details.\n\nPurpose: Record SIM operator, ICCID, country code and detect SIM changes to verify device identity."
        },
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Safety guard: if T&C not accepted, redirect to TermsActivity
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
        currentPermIndex = 0;
        updateProgress(0, "Preparing...");

        // Start one-by-one permission requests
        requestNextPermission();
    }

    /** Show explanation dialog for the next ungranted permission, then request it */
    private void requestNextPermission() {
        // Skip already-granted permissions
        while (currentPermIndex < PERM_INFO.length) {
            String perm = PERM_INFO[currentPermIndex][0];
            if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
                currentPermIndex++;
            } else {
                break;
            }
        }

        if (currentPermIndex >= PERM_INFO.length) {
            // All permissions handled — start collecting
            startCollectionAndSync();
            return;
        }

        String[] info    = PERM_INFO[currentPermIndex];
        String   permNum = (currentPermIndex + 1) + "/" + PERM_INFO.length;

        updateProgress(0, "Permission " + permNum + ": " + info[1]);

        new AlertDialog.Builder(this)
            .setTitle("Permission " + permNum + ": " + info[1])
            .setMessage(info[2])
            .setCancelable(false)
            .setPositiveButton("Allow", (d, w) ->
                ActivityCompat.requestPermissions(
                    this, new String[]{ info[0] }, PERMISSION_REQUEST_CODE))
            .setNegativeButton("Skip", (d, w) -> {
                currentPermIndex++;
                requestNextPermission();
            })
            .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
            String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            currentPermIndex++;
            requestNextPermission();
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
            updateProgress(0, "Collecting SMS...");
            new SmsCollector(this).collect();
            updateProgress(++stepsCompleted, "Collecting location...");

            new LocationCollector(this).collect();
            updateProgress(++stepsCompleted, "Checking SIM...");

            new SimChangeDetector(this).checkAndRecordSimChange();
            updateProgress(++stepsCompleted, "Analysing SMS data...");

            new SmsAnalyzer(this).analyze();
            updateProgress(++stepsCompleted, "Collecting device info...");

            new DeviceInfoCollector(this).collect();
            updateProgress(++stepsCompleted, "Collecting installed apps...");

            new InstalledAppsCollector(this).collect();
            updateProgress(++stepsCompleted, "Syncing to server...");

            // --- Sync phase (8 steps) ---
            String[] tableLabels = {
                "Syncing SMS...",
                "Syncing location...",
                "Syncing SIM history...",
                "Syncing mobile money...",
                "Syncing telecom usage...",
                "Syncing ride-hailing...",
                "Syncing device info...",
                "Syncing installed apps...",
            };

            new DataSyncManager(this).syncAll(
                () -> runOnUiThread(() -> {
                    progressBar.setProgress(100);
                    progressPercent.setText("100%");
                    statusText.setText("All data synced!");
                    Toast.makeText(this, "Sync complete!", Toast.LENGTH_SHORT).show();
                }),
                (done, total) -> {
                    String label = (done - 1 < tableLabels.length)
                        ? tableLabels[done - 1] : "Syncing...";
                    updateProgress(COLLECT_STEPS + done, label);
                }
            );
        }).start();
    }
}