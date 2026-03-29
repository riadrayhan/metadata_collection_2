package com.datacollector;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class SimChangeDetector {

    private final Context context;
    private final DatabaseHelper db;
    private static final String PREFS_NAME = "sim_prefs";
    private static final String KEY_SIM_FINGERPRINT = "saved_sim_fingerprint";

    public SimChangeDetector(Context context) {
        this.context = context;
        this.db = DatabaseHelper.getInstance(context);
    }

    public void checkAndRecordSimChange() {
        try {
            TelephonyManager tm = (TelephonyManager)
                context.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) return;

            // Gather SIM info from multiple sources
            String iccid = null;
            String carrier = tm.getSimOperatorName();
            String simOperator = tm.getSimOperator(); // MCC+MNC
            String phoneNumber = "";
            int simSlot = 0;
            String subscriptionId = "";

            // Try ICCID from TelephonyManager (works on Android < 10)
            try {
                iccid = tm.getSimSerialNumber();
            } catch (SecurityException ignored) {}

            // Try SubscriptionManager for more info (API 22+)
            try {
                SubscriptionManager sm = (SubscriptionManager)
                    context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
                if (sm != null) {
                    List<SubscriptionInfo> subs = sm.getActiveSubscriptionInfoList();
                    if (subs != null && !subs.isEmpty()) {
                        for (SubscriptionInfo info : subs) {
                            simSlot = info.getSimSlotIndex();
                            subscriptionId = String.valueOf(info.getSubscriptionId());
                            CharSequence cn = info.getCarrierName();
                            if (cn != null && cn.length() > 0) carrier = cn.toString();
                            CharSequence dn = info.getDisplayName();
                            // Try getting ICCID from SubscriptionInfo
                            try {
                                String subIccid = info.getIccId();
                                if (subIccid != null && !subIccid.isEmpty()) {
                                    iccid = subIccid;
                                }
                            } catch (Exception ignored) {}
                            try {
                                String num = info.getNumber();
                                if (num != null && !num.isEmpty()) phoneNumber = num;
                            } catch (Exception ignored) {}

                            // Record each active SIM
                            String simFingerprint = buildFingerprint(iccid, subscriptionId, simOperator, simSlot);
                            recordSim(simFingerprint, iccid, phoneNumber, carrier, simOperator, simSlot, subscriptionId);
                        }
                        return; // Handled via SubscriptionManager
                    }
                }
            } catch (SecurityException ignored) {}

            // Fallback: use TelephonyManager data only
            try {
                phoneNumber = tm.getLine1Number();
                if (phoneNumber == null) phoneNumber = "";
            } catch (SecurityException ignored) {}

            // Build a fingerprint from whatever we have
            String fingerprint = buildFingerprint(iccid, "", simOperator, 0);
            if (fingerprint.isEmpty()) return; // No SIM data at all

            recordSim(fingerprint, iccid, phoneNumber, carrier, simOperator, 0, "");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String buildFingerprint(String iccid, String subId, String simOp, int slot) {
        // Use ICCID if available, otherwise build from operator + subscription
        if (iccid != null && !iccid.isEmpty()) return iccid;
        if (simOp != null && !simOp.isEmpty()) return simOp + "_" + subId + "_" + slot;
        if (subId != null && !subId.isEmpty()) return "sub_" + subId;
        return "";
    }

    private void recordSim(String fingerprint, String iccid,
                           String phoneNumber, String carrier,
                           String simOperator, int simSlot, String subscriptionId) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String savedFingerprint = prefs.getString(KEY_SIM_FINGERPRINT, null);

        String timestamp = new SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss", Locale.getDefault()
        ).format(new Date());

        String displayIccid = (iccid != null && !iccid.isEmpty())
            ? iccid : "restricted_" + simOperator;

        if (savedFingerprint == null) {
            // First time — record initial SIM
            db.insertSimChange("INITIAL", displayIccid,
                phoneNumber, carrier, timestamp);
            prefs.edit().putString(KEY_SIM_FINGERPRINT, fingerprint).apply();

        } else if (!savedFingerprint.equals(fingerprint)) {
            // SIM changed
            db.insertSimChange(savedFingerprint, displayIccid,
                phoneNumber, carrier, timestamp);
            prefs.edit().putString(KEY_SIM_FINGERPRINT, fingerprint).apply();
        }
    }
}
