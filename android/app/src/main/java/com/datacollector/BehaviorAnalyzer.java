package com.datacollector;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.provider.CallLog;
import android.provider.Telephony;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Behavioral stability analyzer — computes credit-risk-relevant metrics from
 * call logs, SMS patterns, and location data:
 * 
 * - Call regularity (how routine the calling pattern is)
 * - Unique contacts diversity
 * - Night-time activity ratio
 * - Incoming vs outgoing ratio (social demand indicator)
 * - SMS frequency patterns
 * - Top-up regularity (from telecom_usage)
 * - Location stability (how many unique locations)
 * - Weekend vs weekday activity
 * - Average call duration
 * - Communication network size
 */
public class BehaviorAnalyzer {

    private final Context context;
    private final DatabaseHelper db;

    public BehaviorAnalyzer(Context context) {
        this.context = context;
        this.db = DatabaseHelper.getInstance(context);
    }

    public void analyze() {
        String timestamp = new SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());

        SQLiteDatabase sqlDb = db.getReadableDatabase();

        // ─── Call Log Analysis ──────────────────────────────────────────
        int totalCalls = 0;
        int incomingCalls = 0;
        int outgoingCalls = 0;
        int missedCalls = 0;
        long totalDuration = 0;
        int nightCalls = 0; // 10pm - 6am
        int weekendCalls = 0;
        Set<String> uniqueContacts = new HashSet<>();
        Map<String, Integer> dailyCallCounts = new HashMap<>(); // date -> count

        try {
            Cursor cursor = sqlDb.rawQuery("SELECT number, type, date, duration FROM " +
                DatabaseHelper.TABLE_CALL_LOGS, null);
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    totalCalls++;
                    String number = cursor.getString(0);
                    String type = cursor.getString(1);
                    String dateStr = cursor.getString(2);
                    String durationStr = cursor.getString(3);

                    if (number != null) uniqueContacts.add(number);

                    if ("INCOMING".equals(type)) incomingCalls++;
                    else if ("OUTGOING".equals(type)) outgoingCalls++;
                    else if ("MISSED".equals(type)) missedCalls++;

                    try {
                        totalDuration += Long.parseLong(durationStr);
                    } catch (Exception ignored) {}

                    // Time-based analysis
                    try {
                        long dateMs = Long.parseLong(dateStr);
                        Calendar cal = Calendar.getInstance();
                        cal.setTimeInMillis(dateMs);
                        int hour = cal.get(Calendar.HOUR_OF_DAY);
                        int day = cal.get(Calendar.DAY_OF_WEEK);

                        if (hour >= 22 || hour < 6) nightCalls++;
                        if (day == Calendar.FRIDAY || day == Calendar.SATURDAY) weekendCalls++;

                        String dayKey = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                            .format(new Date(dateMs));
                        dailyCallCounts.merge(dayKey, 1, Integer::sum);
                    } catch (Exception ignored) {}
                }
                cursor.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // ─── SMS Analysis ───────────────────────────────────────────────
        int totalSms = 0;
        int sentSms = 0;
        int receivedSms = 0;
        Set<String> uniqueSmsContacts = new HashSet<>();

        try {
            Cursor cursor = sqlDb.rawQuery("SELECT address, type FROM " +
                DatabaseHelper.TABLE_SMS, null);
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    totalSms++;
                    String address = cursor.getString(0);
                    String type = cursor.getString(1);
                    if (address != null) uniqueSmsContacts.add(address);
                    if ("SENT".equals(type)) sentSms++;
                    else if ("RECEIVED".equals(type)) receivedSms++;
                }
                cursor.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // ─── Location Stability ─────────────────────────────────────────
        int uniqueLocations = 0;
        try {
            Cursor cursor = sqlDb.rawQuery(
                "SELECT COUNT(DISTINCT ROUND(latitude,3) || ',' || ROUND(longitude,3)) FROM " +
                DatabaseHelper.TABLE_LOCATION, null);
            if (cursor != null && cursor.moveToFirst()) {
                uniqueLocations = cursor.getInt(0);
                cursor.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // ─── Mobile Money Activity ──────────────────────────────────────
        int totalMobileMoneyTxns = 0;
        double totalMobileMoneyVolume = 0;
        try {
            Cursor cursor = sqlDb.rawQuery(
                "SELECT COUNT(*), COALESCE(SUM(CAST(amount AS REAL)),0) FROM " +
                DatabaseHelper.TABLE_MOBILE_MONEY, null);
            if (cursor != null && cursor.moveToFirst()) {
                totalMobileMoneyTxns = cursor.getInt(0);
                totalMobileMoneyVolume = cursor.getDouble(1);
                cursor.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // ─── Telecom Recharge Activity ──────────────────────────────────
        int totalRecharges = 0;
        double totalRechargeAmount = 0;
        try {
            Cursor cursor = sqlDb.rawQuery(
                "SELECT COUNT(*), COALESCE(SUM(CAST(amount AS REAL)),0) FROM " +
                DatabaseHelper.TABLE_TELECOM_USAGE + " WHERE recharge_type='RECHARGE'", null);
            if (cursor != null && cursor.moveToFirst()) {
                totalRecharges = cursor.getInt(0);
                totalRechargeAmount = cursor.getDouble(1);
                cursor.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // ─── Compute Scores ─────────────────────────────────────────────

        // Call regularity: std dev of daily call counts (lower = more regular)
        double callRegularity = computeRegularity(dailyCallCounts);

        // Incoming/outgoing ratio (social demand)
        double inOutRatio = outgoingCalls > 0 ? (double) incomingCalls / outgoingCalls : 0;

        // Night activity ratio
        double nightRatio = totalCalls > 0 ? (double) nightCalls / totalCalls : 0;

        // Weekend activity ratio
        double weekendRatio = totalCalls > 0 ? (double) weekendCalls / totalCalls : 0;

        // Average call duration
        double avgCallDuration = totalCalls > 0 ? (double) totalDuration / totalCalls : 0;

        // Contact diversity (unique contacts / total calls)
        double contactDiversity = totalCalls > 0 ?
            (double) uniqueContacts.size() / totalCalls : 0;

        // Communication network size
        Set<String> allContacts = new HashSet<>(uniqueContacts);
        allContacts.addAll(uniqueSmsContacts);
        int networkSize = allContacts.size();

        // Mobile money activity score (normalized)
        double mfsActivityScore = Math.min(totalMobileMoneyTxns / 100.0, 1.0);

        // Recharge regularity
        double rechargeFreq = totalRecharges > 0 ? totalRecharges : 0;

        db.insertBehaviorScore(
            totalCalls, incomingCalls, outgoingCalls, missedCalls,
            totalDuration, nightCalls, weekendCalls,
            uniqueContacts.size(), String.format(Locale.US, "%.2f", callRegularity),
            String.format(Locale.US, "%.2f", inOutRatio),
            String.format(Locale.US, "%.3f", nightRatio),
            String.format(Locale.US, "%.3f", weekendRatio),
            String.format(Locale.US, "%.1f", avgCallDuration),
            String.format(Locale.US, "%.3f", contactDiversity),
            totalSms, sentSms, receivedSms, uniqueSmsContacts.size(),
            networkSize, uniqueLocations,
            totalMobileMoneyTxns, String.format(Locale.US, "%.2f", totalMobileMoneyVolume),
            totalRecharges, String.format(Locale.US, "%.2f", totalRechargeAmount),
            String.format(Locale.US, "%.2f", mfsActivityScore),
            String.format(Locale.US, "%.1f", rechargeFreq),
            timestamp
        );
    }

    private double computeRegularity(Map<String, Integer> dailyCounts) {
        if (dailyCounts.isEmpty()) return 0;

        double sum = 0;
        for (int count : dailyCounts.values()) sum += count;
        double mean = sum / dailyCounts.size();

        double variance = 0;
        for (int count : dailyCounts.values()) {
            variance += Math.pow(count - mean, 2);
        }
        variance /= dailyCounts.size();

        // Coefficient of variation (lower = more regular)
        double stdDev = Math.sqrt(variance);
        return mean > 0 ? stdDev / mean : 0;
    }
}
