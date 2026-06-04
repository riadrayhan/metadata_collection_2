package com.datacollector;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.media.ExifInterface;
import android.net.Uri;
import android.provider.MediaStore;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Scans all photos on the device and extracts GPS coordinates embedded in
 * EXIF metadata. Each photo with a GPS tag produces one historical location
 * data point (lat, lng, timestamp), giving a real picture of where the user
 * has been over months / years.
 *
 * Requires READ_MEDIA_IMAGES (API 33+) or READ_EXTERNAL_STORAGE (API 26-32).
 */
public class PhotoLocationCollector {

    private final Context     context;
    private final DatabaseHelper db;

    public PhotoLocationCollector(Context context) {
        this.context = context;
        this.db      = DatabaseHelper.getInstance(context);
    }

    public void collect() {
        String[] projection = {
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
        };

        String sortOrder = MediaStore.Images.Media.DATE_TAKEN + " ASC";

        try (Cursor cursor = context.getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, null, null, sortOrder)) {

            if (cursor == null) return;

            int idCol   = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
            int dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN);

            SimpleDateFormat sdf =
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

            while (cursor.moveToNext()) {
                long id          = cursor.getLong(idCol);
                long dateTakenMs = cursor.getLong(dateCol);

                Uri photoUri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);

                try (InputStream stream =
                        context.getContentResolver().openInputStream(photoUri)) {

                    if (stream == null) continue;

                    ExifInterface exif   = new ExifInterface(stream);
                    float[]       latLng = new float[2];
                    boolean       hasGps = exif.getLatLong(latLng);

                    if (!hasGps) continue;

                    double lat = latLng[0];
                    double lng = latLng[1];

                    // Skip (0, 0) — almost certainly a missing/bad GPS fix
                    if (lat == 0.0 && lng == 0.0) continue;

                    String timestamp = dateTakenMs > 0
                        ? sdf.format(new Date(dateTakenMs))
                        : sdf.format(new Date());

                    db.insertPhotoLocation(lat, lng, timestamp);

                } catch (Exception ignored) {
                    // Skip photos that can't be opened or have corrupt EXIF
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
