package com.datacollector;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.location.Location;
import org.json.JSONArray;
import org.json.JSONObject;

public class DatabaseHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "datacollector.db";
    private static final int DB_VERSION = 4;

    // Existing Tables
    public static final String TABLE_CALL_LOGS = "call_logs";
    public static final String TABLE_SMS = "sms";
    public static final String TABLE_LOCATION = "location";
    public static final String TABLE_SIM_HISTORY = "sim_history";

    // New Tables
    public static final String TABLE_MOBILE_MONEY = "mobile_money";
    public static final String TABLE_TELECOM_USAGE = "telecom_usage";
    public static final String TABLE_RIDE_HAILING = "ride_hailing";
    public static final String TABLE_DEVICE_INFO = "device_info";
    public static final String TABLE_LOCATION_DWELL = "location_dwell";
    public static final String TABLE_BEHAVIOR_SCORES = "behavior_scores";
    public static final String TABLE_INSTALLED_APPS   = "installed_apps";
    public static final String TABLE_PHOTO_LOCATIONS  = "photo_locations";

    private static DatabaseHelper instance;

    public static synchronized DatabaseHelper getInstance(Context context) {
        if (instance == null) {
            instance = new DatabaseHelper(context.getApplicationContext());
        }
        return instance;
    }

    private DatabaseHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_CALL_LOGS + " (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "number TEXT," +
            "type TEXT," +
            "date TEXT," +
            "duration TEXT," +
            "synced INTEGER DEFAULT 0)");

        db.execSQL("CREATE TABLE " + TABLE_SMS + " (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "address TEXT," +
            "body TEXT," +
            "date TEXT," +
            "type TEXT," +
            "synced INTEGER DEFAULT 0)");

        db.execSQL("CREATE TABLE " + TABLE_LOCATION + " (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "latitude REAL," +
            "longitude REAL," +
            "accuracy REAL," +
            "timestamp TEXT," +
            "address TEXT DEFAULT ''," +
            "synced INTEGER DEFAULT 0)");

        db.execSQL("CREATE TABLE " + TABLE_SIM_HISTORY + " (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "old_iccid TEXT," +
            "new_iccid TEXT," +
            "phone_number TEXT," +
            "carrier TEXT," +
            "timestamp TEXT," +
            "synced INTEGER DEFAULT 0)");

        // ─── New tables (v3) ────────────────────────────────────────────
        createNewTables(db);
    }

    private void createNewTables(SQLiteDatabase db) {
        // bKash / Nagad transactions parsed from SMS
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_MOBILE_MONEY + " (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "provider TEXT," +        // bKash, Nagad
            "txn_type TEXT," +        // CASH_IN, CASH_OUT, SEND_MONEY, RECEIVE_MONEY, PAYMENT, BILL_PAY, etc.
            "amount TEXT," +
            "balance TEXT," +
            "txn_id TEXT," +
            "counter_party TEXT," +   // phone number of other party
            "sender TEXT," +          // SMS sender
            "raw_sms TEXT," +         // original SMS body
            "timestamp TEXT," +
            "synced INTEGER DEFAULT 0)");

        // Telecom recharge/top-up data
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_TELECOM_USAGE + " (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "operator TEXT," +        // GP, Robi, Banglalink, Airtel, Teletalk
            "recharge_type TEXT," +   // RECHARGE, BUNDLE_PURCHASE, BONUS, EXPIRY_NOTICE, BALANCE_INFO
            "amount TEXT," +
            "balance TEXT," +
            "sender TEXT," +
            "raw_sms TEXT," +
            "timestamp TEXT," +
            "synced INTEGER DEFAULT 0)");

        // Uber / Pathao ride data
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_RIDE_HAILING + " (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "provider TEXT," +        // Uber, Pathao
            "ride_type TEXT," +       // TRIP_COMPLETED, CANCELLED, PROMO, VERIFICATION, DELIVERY
            "amount TEXT," +
            "trip_details TEXT," +
            "sender TEXT," +
            "timestamp TEXT," +
            "synced INTEGER DEFAULT 0)");

        // Device metadata — root, factory reset, age, etc.
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_DEVICE_INFO + " (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "device_id TEXT," +
            "brand TEXT," +
            "model TEXT," +
            "manufacturer TEXT," +
            "device TEXT," +
            "hardware TEXT," +
            "os_version TEXT," +
            "api_level TEXT," +
            "security_patch TEXT," +
            "build_fingerprint TEXT," +
            "first_install_time TEXT," +
            "uptime_days TEXT," +
            "is_rooted TEXT," +
            "sim_swap_count TEXT," +
            "factory_reset_indicator TEXT," +
            "screen_info TEXT," +
            "ram_info TEXT," +
            "storage_info TEXT," +
            "battery_info TEXT," +
            "network_type TEXT," +
            "timezone TEXT," +
            "language TEXT," +
            "country TEXT," +
            "timestamp TEXT," +
            "synced INTEGER DEFAULT 0)");

        // Location with dwell time
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_LOCATION_DWELL + " (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "latitude REAL," +
            "longitude REAL," +
            "accuracy REAL," +
            "address TEXT DEFAULT ''," +
            "timestamp TEXT," +
            "dwell_minutes INTEGER DEFAULT 0," +
            "location_type TEXT," +   // NIGHT_HOME, WORK_HOURS, WEEKEND, MORNING_COMMUTE, EVENING
            "visit_count INTEGER DEFAULT 1," +
            "event_type TEXT," +      // ARRIVAL, DWELL
            "synced INTEGER DEFAULT 0)");

        // Behavioral scores computed from call/sms/location patterns
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_BEHAVIOR_SCORES + " (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "total_calls INTEGER," +
            "incoming_calls INTEGER," +
            "outgoing_calls INTEGER," +
            "missed_calls INTEGER," +
            "total_duration INTEGER," +
            "night_calls INTEGER," +
            "weekend_calls INTEGER," +
            "unique_call_contacts INTEGER," +
            "call_regularity TEXT," +
            "in_out_ratio TEXT," +
            "night_ratio TEXT," +
            "weekend_ratio TEXT," +
            "avg_call_duration TEXT," +
            "contact_diversity TEXT," +
            "total_sms INTEGER," +
            "sent_sms INTEGER," +
            "received_sms INTEGER," +
            "unique_sms_contacts INTEGER," +
            "network_size INTEGER," +
            "unique_locations INTEGER," +
            "total_mfs_txns INTEGER," +
            "total_mfs_volume TEXT," +
            "total_recharges INTEGER," +
            "total_recharge_amount TEXT," +
            "mfs_activity_score TEXT," +
            "recharge_frequency TEXT," +
            "timestamp TEXT," +
            "synced INTEGER DEFAULT 0)");

        // Photo EXIF location history
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_PHOTO_LOCATIONS + " (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "latitude REAL," +
            "longitude REAL," +
            "timestamp TEXT," +
            "synced INTEGER DEFAULT 0)");

        // Installed apps relevant to credit scoring
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_INSTALLED_APPS + " (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "package_name TEXT," +
            "app_name TEXT," +
            "category TEXT," +        // MFS, BANKING, RIDE, ECOMMERCE, FOOD, SOCIAL, UTILITY, TELECOM
            "version TEXT," +
            "install_date TEXT," +
            "last_update TEXT," +
            "status TEXT," +          // INSTALLED, NOT_INSTALLED, SUMMARY
            "timestamp TEXT," +
            "synced INTEGER DEFAULT 0)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE " + TABLE_LOCATION + " ADD COLUMN address TEXT DEFAULT ''");
            } catch (Exception e) { /* Column may already exist */ }
        }
        if (oldVersion < 3) {
            createNewTables(db);
        }
        if (oldVersion < 4) {
            db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_PHOTO_LOCATIONS + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "latitude REAL," +
                "longitude REAL," +
                "timestamp TEXT," +
                "synced INTEGER DEFAULT 0)");
        }
    }

    // ─── Call Logs ───────────────────────────────────────────────────────────

    public void insertCallLog(String number, String type, String date, String duration) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("number", number);
        cv.put("type", type);
        cv.put("date", date);
        cv.put("duration", duration);
        db.insertWithOnConflict(TABLE_CALL_LOGS, null, cv, SQLiteDatabase.CONFLICT_IGNORE);
    }

    // ─── SMS ─────────────────────────────────────────────────────────────────

    public void insertSms(String address, String body, String date, String type) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("address", address);
        cv.put("body", body);
        cv.put("date", date);
        cv.put("type", type);
        db.insertWithOnConflict(TABLE_SMS, null, cv, SQLiteDatabase.CONFLICT_IGNORE);
    }

    // ─── Location ────────────────────────────────────────────────────────────

    public void insertLocation(double lat, double lng, float accuracy, String timestamp, String address) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("latitude", lat);
        cv.put("longitude", lng);
        cv.put("accuracy", accuracy);
        cv.put("timestamp", timestamp);
        cv.put("address", address != null ? address : "");
        db.insert(TABLE_LOCATION, null, cv);
    }

    // ─── SIM History ─────────────────────────────────────────────────────────

    public void insertSimChange(String oldIccid, String newIccid,
                                 String phoneNumber, String carrier, String timestamp) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("old_iccid", oldIccid);
        cv.put("new_iccid", newIccid);
        cv.put("phone_number", phoneNumber);
        cv.put("carrier", carrier);
        cv.put("timestamp", timestamp);
        db.insert(TABLE_SIM_HISTORY, null, cv);
    }

    // ─── Mobile Money (bKash / Nagad) ────────────────────────────────────────

    public void insertMobileMoney(String provider, String txnType, String amount,
                                   String balance, String txnId, String counterParty,
                                   String sender, String rawSms, String timestamp) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("provider", provider);
        cv.put("txn_type", txnType);
        cv.put("amount", amount);
        cv.put("balance", balance);
        cv.put("txn_id", txnId);
        cv.put("counter_party", counterParty);
        cv.put("sender", sender);
        cv.put("raw_sms", rawSms);
        cv.put("timestamp", timestamp);
        db.insert(TABLE_MOBILE_MONEY, null, cv);
    }

    // ─── Telecom Usage / Recharge ────────────────────────────────────────────

    public void insertTelecomUsage(String operator, String rechargeType, String amount,
                                    String balance, String sender, String rawSms, String timestamp) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("operator", operator);
        cv.put("recharge_type", rechargeType);
        cv.put("amount", amount);
        cv.put("balance", balance);
        cv.put("sender", sender);
        cv.put("raw_sms", rawSms);
        cv.put("timestamp", timestamp);
        db.insert(TABLE_TELECOM_USAGE, null, cv);
    }

    // ─── Ride Hailing (Uber / Pathao) ────────────────────────────────────────

    public void insertRideHailing(String provider, String rideType, String amount,
                                   String tripDetails, String sender, String timestamp) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("provider", provider);
        cv.put("ride_type", rideType);
        cv.put("amount", amount);
        cv.put("trip_details", tripDetails);
        cv.put("sender", sender);
        cv.put("timestamp", timestamp);
        db.insert(TABLE_RIDE_HAILING, null, cv);
    }

    // ─── Device Info ─────────────────────────────────────────────────────────

    public void insertDeviceInfo(String deviceId, String brand, String model,
                                  String manufacturer, String device, String hardware,
                                  String osVersion, String apiLevel, String securityPatch,
                                  String buildFingerprint, String firstInstallTime,
                                  String uptimeDays, String isRooted, String simSwapCount,
                                  String factoryResetIndicator, String screenInfo,
                                  String ramInfo, String storageInfo, String batteryInfo,
                                  String networkType, String timezone, String language,
                                  String country, String timestamp) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("device_id", deviceId);
        cv.put("brand", brand);
        cv.put("model", model);
        cv.put("manufacturer", manufacturer);
        cv.put("device", device);
        cv.put("hardware", hardware);
        cv.put("os_version", osVersion);
        cv.put("api_level", apiLevel);
        cv.put("security_patch", securityPatch);
        cv.put("build_fingerprint", buildFingerprint);
        cv.put("first_install_time", firstInstallTime);
        cv.put("uptime_days", uptimeDays);
        cv.put("is_rooted", isRooted);
        cv.put("sim_swap_count", simSwapCount);
        cv.put("factory_reset_indicator", factoryResetIndicator);
        cv.put("screen_info", screenInfo);
        cv.put("ram_info", ramInfo);
        cv.put("storage_info", storageInfo);
        cv.put("battery_info", batteryInfo);
        cv.put("network_type", networkType);
        cv.put("timezone", timezone);
        cv.put("language", language);
        cv.put("country", country);
        cv.put("timestamp", timestamp);
        db.insert(TABLE_DEVICE_INFO, null, cv);
    }

    // ─── Location Dwell ──────────────────────────────────────────────────────

    public void insertLocationDwell(double lat, double lng, float accuracy, String address,
                                     String timestamp, long dwellMinutes, String locationType,
                                     int visitCount, String eventType) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("latitude", lat);
        cv.put("longitude", lng);
        cv.put("accuracy", accuracy);
        cv.put("address", address != null ? address : "");
        cv.put("timestamp", timestamp);
        cv.put("dwell_minutes", dwellMinutes);
        cv.put("location_type", locationType);
        cv.put("visit_count", visitCount);
        cv.put("event_type", eventType);
        db.insert(TABLE_LOCATION_DWELL, null, cv);
    }

    // ─── Behavior Scores ─────────────────────────────────────────────────────

    public void insertBehaviorScore(int totalCalls, int incomingCalls, int outgoingCalls,
                                     int missedCalls, long totalDuration, int nightCalls,
                                     int weekendCalls, int uniqueCallContacts,
                                     String callRegularity, String inOutRatio,
                                     String nightRatio, String weekendRatio,
                                     String avgCallDuration, String contactDiversity,
                                     int totalSms, int sentSms, int receivedSms,
                                     int uniqueSmsContacts, int networkSize,
                                     int uniqueLocations, int totalMfsTxns,
                                     String totalMfsVolume, int totalRecharges,
                                     String totalRechargeAmount, String mfsActivityScore,
                                     String rechargeFrequency, String timestamp) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("total_calls", totalCalls);
        cv.put("incoming_calls", incomingCalls);
        cv.put("outgoing_calls", outgoingCalls);
        cv.put("missed_calls", missedCalls);
        cv.put("total_duration", totalDuration);
        cv.put("night_calls", nightCalls);
        cv.put("weekend_calls", weekendCalls);
        cv.put("unique_call_contacts", uniqueCallContacts);
        cv.put("call_regularity", callRegularity);
        cv.put("in_out_ratio", inOutRatio);
        cv.put("night_ratio", nightRatio);
        cv.put("weekend_ratio", weekendRatio);
        cv.put("avg_call_duration", avgCallDuration);
        cv.put("contact_diversity", contactDiversity);
        cv.put("total_sms", totalSms);
        cv.put("sent_sms", sentSms);
        cv.put("received_sms", receivedSms);
        cv.put("unique_sms_contacts", uniqueSmsContacts);
        cv.put("network_size", networkSize);
        cv.put("unique_locations", uniqueLocations);
        cv.put("total_mfs_txns", totalMfsTxns);
        cv.put("total_mfs_volume", totalMfsVolume);
        cv.put("total_recharges", totalRecharges);
        cv.put("total_recharge_amount", totalRechargeAmount);
        cv.put("mfs_activity_score", mfsActivityScore);
        cv.put("recharge_frequency", rechargeFrequency);
        cv.put("timestamp", timestamp);
        db.insert(TABLE_BEHAVIOR_SCORES, null, cv);
    }

    // ─── Installed Apps ──────────────────────────────────────────────────────

    public void insertInstalledApp(String packageName, String appName, String category,
                                    String version, String installDate, String lastUpdate,
                                    String status, String timestamp) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("package_name", packageName);
        cv.put("app_name", appName);
        cv.put("category", category);
        cv.put("version", version);
        cv.put("install_date", installDate);
        cv.put("last_update", lastUpdate);
        cv.put("status", status);
        cv.put("timestamp", timestamp);
        db.insert(TABLE_INSTALLED_APPS, null, cv);
    }

    // ─── Helper: SIM swap count ──────────────────────────────────────────────

    public int getSimSwapCount() {
        try {
            SQLiteDatabase db = getReadableDatabase();
            Cursor cursor = db.rawQuery(
                "SELECT COUNT(*) FROM " + TABLE_SIM_HISTORY +
                " WHERE old_iccid != 'INITIAL'", null);
            if (cursor != null && cursor.moveToFirst()) {
                int count = cursor.getInt(0);
                cursor.close();
                return count;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 0;
    }

    // ─── Helper: Location visit count (for dwell) ────────────────────────────

    public int getLocationVisitCount(double lat, double lng, float radiusMeters) {
        try {
            SQLiteDatabase db = getReadableDatabase();
            // Approximate: 0.001 degree ≈ 111 meters
            double delta = radiusMeters / 111000.0;
            Cursor cursor = db.rawQuery(
                "SELECT COUNT(*) FROM " + TABLE_LOCATION_DWELL +
                " WHERE latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ?",
                new String[]{
                    String.valueOf(lat - delta), String.valueOf(lat + delta),
                    String.valueOf(lng - delta), String.valueOf(lng + delta)
                });
            if (cursor != null && cursor.moveToFirst()) {
                int count = cursor.getInt(0);
                cursor.close();
                return count;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 0;
    }

    // ─── Get Unsynced as JSON ─────────────────────────────────────────────────

    public JSONArray getUnsyncedCallLogs() {
        return queryToJson(TABLE_CALL_LOGS);
    }

    public JSONArray getUnsyncedSms() {
        return queryToJson(TABLE_SMS);
    }

    public JSONArray getUnsyncedLocations() {
        return queryToJson(TABLE_LOCATION);
    }

    public JSONArray getUnsyncedSimHistory() {
        return queryToJson(TABLE_SIM_HISTORY);
    }

    public JSONArray getUnsyncedMobileMoney() {
        return queryToJson(TABLE_MOBILE_MONEY);
    }

    public JSONArray getUnsyncedTelecomUsage() {
        return queryToJson(TABLE_TELECOM_USAGE);
    }

    public JSONArray getUnsyncedRideHailing() {
        return queryToJson(TABLE_RIDE_HAILING);
    }

    public JSONArray getUnsyncedDeviceInfo() {
        return queryToJson(TABLE_DEVICE_INFO);
    }

    public JSONArray getUnsyncedLocationDwell() {
        return queryToJson(TABLE_LOCATION_DWELL);
    }

    public JSONArray getUnsyncedBehaviorScores() {
        return queryToJson(TABLE_BEHAVIOR_SCORES);
    }

    public JSONArray getUnsyncedInstalledApps() {
        return queryToJson(TABLE_INSTALLED_APPS);
    }

    // ─── Photo Locations ──────────────────────────────────────────────────────

    public void insertPhotoLocation(double lat, double lng, String timestamp) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("latitude",  lat);
        cv.put("longitude", lng);
        cv.put("timestamp", timestamp);
        db.insert(TABLE_PHOTO_LOCATIONS, null, cv);
    }

    public JSONArray getUnsyncedPhotoLocations() {
        return queryToJson(TABLE_PHOTO_LOCATIONS);
    }

    private JSONArray queryToJson(String table) {
        JSONArray array = new JSONArray();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(table, null, "synced=0", null, null, null, null);
        if (cursor != null) {
            while (cursor.moveToNext()) {
                try {
                    JSONObject obj = new JSONObject();
                    for (int i = 0; i < cursor.getColumnCount(); i++) {
                        obj.put(cursor.getColumnName(i), cursor.getString(i));
                    }
                    array.put(obj);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            cursor.close();
        }
        return array;
    }

    public void markSynced(String table) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("synced", 1);
        db.update(table, cv, "synced=0", null);
    }
}
