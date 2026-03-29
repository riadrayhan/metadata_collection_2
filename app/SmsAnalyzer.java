package com.datacollector;

import android.content.Context;
import android.database.Cursor;
import android.provider.Telephony;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses SMS messages to extract:
 * - bKash transactions
 * - Nagad transactions
 * - Telecom recharge/top-up (GP, Robi, Banglalink, Airtel, Teletalk)
 * - Uber/Pathao ride data
 */
public class SmsAnalyzer {

    private final Context context;
    private final DatabaseHelper db;

    // bKash sender patterns
    private static final String[] BKASH_SENDERS = {"bKash", "16247", "01234016247"};
    // Nagad sender patterns
    private static final String[] NAGAD_SENDERS = {"Nagad", "16167", "01234016167"};
    // Uber senders
    private static final String[] UBER_SENDERS = {"Uber"};
    // Pathao senders
    private static final String[] PATHAO_SENDERS = {"Pathao"};
    // Telecom operator senders
    private static final String[] TELECOM_SENDERS = {
        "GP", "Grameenphone", "16800", "Robi", "16222",
        "Banglalink", "16616", "Airtel", "16746", "Teletalk", "16400"
    };

    // Amount regex - matches Tk/BDT amounts like "Tk 500.00", "BDT 1,200.50", "Tk.500"
    private static final Pattern AMOUNT_PATTERN = Pattern.compile(
        "(?:Tk\\.?|BDT|Taka)\\s*[:\\.]?\\s*([\\d,]+\\.?\\d*)", Pattern.CASE_INSENSITIVE);

    // Balance regex
    private static final Pattern BALANCE_PATTERN = Pattern.compile(
        "(?:balance|bal|remaining)[:\\s]*(?:Tk\\.?|BDT)?\\s*([\\d,]+\\.?\\d*)", Pattern.CASE_INSENSITIVE);

    // Transaction ID regex
    private static final Pattern TXN_ID_PATTERN = Pattern.compile(
        "(?:TrxID|Txn|Transaction\\s*(?:ID|No))[:\\s]*([A-Za-z0-9]+)", Pattern.CASE_INSENSITIVE);

    // Phone number in SMS
    private static final Pattern PHONE_PATTERN = Pattern.compile(
        "(?:01[3-9]\\d{8})");

    public SmsAnalyzer(Context context) {
        this.context = context;
        this.db = DatabaseHelper.getInstance(context);
    }

    public void analyze() {
        try {
            Cursor cursor = context.getContentResolver().query(
                Telephony.Sms.CONTENT_URI,
                null, null, null,
                Telephony.Sms.DATE + " DESC"
            );

            if (cursor != null) {
                int colAddress = cursor.getColumnIndex(Telephony.Sms.ADDRESS);
                int colBody    = cursor.getColumnIndex(Telephony.Sms.BODY);
                int colDate    = cursor.getColumnIndex(Telephony.Sms.DATE);
                int colType    = cursor.getColumnIndex(Telephony.Sms.TYPE);

                while (cursor.moveToNext()) {
                    String address = cursor.getString(colAddress);
                    String body    = cursor.getString(colBody);
                    String date    = cursor.getString(colDate);
                    int    type    = Integer.parseInt(cursor.getString(colType));

                    if (address == null || body == null) continue;

                    String upperAddr = address.toUpperCase();
                    String upperBody = body.toUpperCase();

                    // Check bKash
                    if (matchesSender(address, BKASH_SENDERS) || upperBody.contains("BKASH")) {
                        parseMobileMoneyTransaction("bKash", address, body, date);
                    }
                    // Check Nagad
                    else if (matchesSender(address, NAGAD_SENDERS) || upperBody.contains("NAGAD")) {
                        parseMobileMoneyTransaction("Nagad", address, body, date);
                    }
                    // Check Uber
                    else if (matchesSender(address, UBER_SENDERS) || upperBody.contains("UBER")) {
                        parseRideTransaction("Uber", address, body, date);
                    }
                    // Check Pathao
                    else if (matchesSender(address, PATHAO_SENDERS) || upperBody.contains("PATHAO")) {
                        parseRideTransaction("Pathao", address, body, date);
                    }
                    // Check Telecom recharge
                    else if (matchesSender(address, TELECOM_SENDERS) || isTelecomRecharge(body)) {
                        parseTelecomTransaction(address, body, date);
                    }
                }
                cursor.close();
            }
        } catch (SecurityException e) {
            e.printStackTrace();
        }
    }

    private boolean matchesSender(String address, String[] senders) {
        for (String sender : senders) {
            if (address.toUpperCase().contains(sender.toUpperCase())) return true;
        }
        return false;
    }

    private boolean isTelecomRecharge(String body) {
        String upper = body.toUpperCase();
        return upper.contains("RECHARGE") || upper.contains("TOP-UP") || upper.contains("TOPUP")
            || upper.contains("RECHARGED") || upper.contains("BUNDLE") || upper.contains("PACK")
            || (upper.contains("BALANCE") && (upper.contains("GP") || upper.contains("ROBI")
            || upper.contains("BANGLALINK") || upper.contains("AIRTEL") || upper.contains("TELETALK")));
    }

    // ─── bKash / Nagad Transaction Parsing ─────────────────────────────────

    private void parseMobileMoneyTransaction(String provider, String sender, String body, String date) {
        String txnType = detectMobileMoneyType(body);
        String amount = extractAmount(body);
        String balance = extractBalance(body);
        String txnId = extractTxnId(body);
        String counterParty = extractPhoneNumber(body);

        String timestamp = formatTimestamp(date);

        db.insertMobileMoney(provider, txnType, amount, balance, txnId,
            counterParty, sender, body, timestamp);
    }

    private String detectMobileMoneyType(String body) {
        String upper = body.toUpperCase();

        if (upper.contains("CASH IN"))        return "CASH_IN";
        if (upper.contains("CASH OUT"))       return "CASH_OUT";
        if (upper.contains("SEND MONEY") || upper.contains("SENT"))    return "SEND_MONEY";
        if (upper.contains("RECEIVED") || upper.contains("RECEIVE"))   return "RECEIVE_MONEY";
        if (upper.contains("PAYMENT") || upper.contains("PAY"))        return "PAYMENT";
        if (upper.contains("BILL") || upper.contains("BILL PAY"))      return "BILL_PAY";
        if (upper.contains("MERCHANT"))       return "MERCHANT_PAYMENT";
        if (upper.contains("RECHARGE"))       return "MOBILE_RECHARGE";
        if (upper.contains("ADD MONEY"))      return "ADD_MONEY";
        if (upper.contains("WITHDRAW"))       return "WITHDRAW";
        if (upper.contains("SALARY"))         return "SALARY";
        if (upper.contains("REMITTANCE"))     return "REMITTANCE";
        return "OTHER";
    }

    // ─── Uber / Pathao Parsing ─────────────────────────────────────────────

    private void parseRideTransaction(String provider, String sender, String body, String date) {
        String rideType = detectRideType(body);
        String amount = extractAmount(body);
        String timestamp = formatTimestamp(date);

        // Try to extract trip details
        String tripDetails = "";
        if (body.length() > 50) {
            tripDetails = body.substring(0, Math.min(body.length(), 200));
        } else {
            tripDetails = body;
        }

        db.insertRideHailing(provider, rideType, amount, tripDetails, sender, timestamp);
    }

    private String detectRideType(String body) {
        String upper = body.toUpperCase();
        if (upper.contains("COMPLETED") || upper.contains("TRIP"))  return "TRIP_COMPLETED";
        if (upper.contains("CANCEL"))    return "CANCELLED";
        if (upper.contains("PROMO") || upper.contains("DISCOUNT")) return "PROMO";
        if (upper.contains("OTP") || upper.contains("CODE"))       return "VERIFICATION";
        if (upper.contains("FOOD") || upper.contains("DELIVERY"))  return "DELIVERY";
        return "OTHER";
    }

    // ─── Telecom Recharge Parsing ──────────────────────────────────────────

    private void parseTelecomTransaction(String sender, String body, String date) {
        String rechargeType = detectRechargeType(body);
        String amount = extractAmount(body);
        String balance = extractBalance(body);
        String operator = detectOperator(sender, body);
        String timestamp = formatTimestamp(date);

        db.insertTelecomUsage(operator, rechargeType, amount, balance,
            sender, body, timestamp);
    }

    private String detectRechargeType(String body) {
        String upper = body.toUpperCase();
        if (upper.contains("RECHARGE") || upper.contains("TOP-UP") || upper.contains("TOPUP"))
            return "RECHARGE";
        if (upper.contains("BUNDLE") || upper.contains("PACK") || upper.contains("INTERNET"))
            return "BUNDLE_PURCHASE";
        if (upper.contains("BONUS"))  return "BONUS";
        if (upper.contains("EXPIRE")) return "EXPIRY_NOTICE";
        if (upper.contains("BALANCE")) return "BALANCE_INFO";
        return "OTHER";
    }

    private String detectOperator(String sender, String body) {
        String combined = (sender + " " + body).toUpperCase();
        if (combined.contains("GP") || combined.contains("GRAMEENPHONE")) return "Grameenphone";
        if (combined.contains("ROBI"))        return "Robi";
        if (combined.contains("BANGLALINK"))  return "Banglalink";
        if (combined.contains("AIRTEL"))      return "Airtel";
        if (combined.contains("TELETALK"))    return "Teletalk";
        return "Unknown";
    }

    // ─── Utility Methods ───────────────────────────────────────────────────

    private String extractAmount(String body) {
        Matcher m = AMOUNT_PATTERN.matcher(body);
        if (m.find()) return m.group(1).replace(",", "");
        return "";
    }

    private String extractBalance(String body) {
        Matcher m = BALANCE_PATTERN.matcher(body);
        if (m.find()) return m.group(1).replace(",", "");
        return "";
    }

    private String extractTxnId(String body) {
        Matcher m = TXN_ID_PATTERN.matcher(body);
        if (m.find()) return m.group(1);
        return "";
    }

    private String extractPhoneNumber(String body) {
        Matcher m = PHONE_PATTERN.matcher(body);
        if (m.find()) return m.group(0);
        return "";
    }

    private String formatTimestamp(String dateMs) {
        try {
            long ms = Long.parseLong(dateMs);
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(ms));
        } catch (Exception e) {
            return dateMs;
        }
    }
}
