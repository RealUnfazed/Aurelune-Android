package app.aurelune.mobile;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.webkit.CookieManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Cover art for the notification: decoding and (as a fallback) downloading. Notification bitmaps must stay small. */
final class Artwork {
    private static final int MAX_PX = 512;
    private static final int MAX_BYTES = 6 * 1024 * 1024;

    private Artwork() {
    }

    /** Decodes an image and shrinks it to at most 512 px on its longest side; null if it can't be decoded. */
    static Bitmap decode(byte[] data) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= MAX_PX && bounds.outHeight / (sample * 2) >= MAX_PX) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
            if (bitmap == null) return null;

            int longest = Math.max(bitmap.getWidth(), bitmap.getHeight());
            if (longest > MAX_PX) {
                float scale = (float) MAX_PX / longest;
                bitmap = Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(bitmap.getWidth() * scale)),
                        Math.max(1, Math.round(bitmap.getHeight() * scale)), true);
            }
            return bitmap;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /** Blocking download: call from a background thread. Works for PNG/JPEG/WebP, not for SVG. */
    static Bitmap download(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            String cookie = CookieManager.getInstance().getCookie(url); // covers of private tracks need your session
            if (cookie != null) connection.setRequestProperty("Cookie", cookie);
            if (connection.getResponseCode() != 200) return null;
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                int total = 0;
                while ((n = in.read(buffer)) > 0) {
                    total += n;
                    if (total > MAX_BYTES) return null;
                    out.write(buffer, 0, n);
                }
                return decode(out.toByteArray());
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
