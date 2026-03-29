package com.datacollector;

import android.content.Context;
import android.database.Cursor;
import android.provider.CallLog;

public class CallLogCollector {

    private final Context context;
    private final DatabaseHelper db;

    public CallLogCollector(Context context) {
        this.context = context;
        this.db = DatabaseHelper.getInstance(context);
    }

    public void collect() {
        try {
            Cursor cursor = context.getContentResolver().query(
                CallLog.Calls.CONTENT_URI,
                null, null, null,
                CallLog.Calls.DATE + " DESC"
            );

            if (cursor != null) {
                int colNumber   = cursor.getColumnIndex(CallLog.Calls.NUMBER);
                int colType     = cursor.getColumnIndex(CallLog.Calls.TYPE);
                int colDate     = cursor.getColumnIndex(CallLog.Calls.DATE);
                int colDuration = cursor.getColumnIndex(CallLog.Calls.DURATION);

                while (cursor.moveToNext()) {
                    String number   = cursor.getString(colNumber);
                    String date     = cursor.getString(colDate);
                    String duration = cursor.getString(colDuration);
                    String typeRaw  = cursor.getString(colType);

                    // Convert type int to label
                    String type;
                    switch (Integer.parseInt(typeRaw)) {
                        case CallLog.Calls.INCOMING_TYPE:  type = "INCOMING";  break;
                        case CallLog.Calls.OUTGOING_TYPE:  type = "OUTGOING";  break;
                        case CallLog.Calls.MISSED_TYPE:    type = "MISSED";    break;
                        case CallLog.Calls.REJECTED_TYPE:  type = "REJECTED";  break;
                        default:                           type = "UNKNOWN";   break;
                    }

                    db.insertCallLog(number, type, date, duration);
                }
                cursor.close();
            }
        } catch (SecurityException e) {
            e.printStackTrace();
        }
    }
}
