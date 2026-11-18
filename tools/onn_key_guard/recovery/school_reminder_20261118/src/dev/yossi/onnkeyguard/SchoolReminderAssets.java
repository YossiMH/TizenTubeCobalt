package dev.yossi.onnkeyguard;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import org.json.JSONObject;

/** Media stays in the app's external-files directory; its pins are inside the signed APK. */
final class SchoolReminderAssets {
    static final String DIRECTORY = "school_reminder_v1";
    static final String MANIFEST = "school_reminder_manifest.json";
    private static final long MAX_MEDIA_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_IMAGE_BYTES = 12L * 1024L * 1024L;

    static final class Loaded {
        final FileInputStream video;
        final FileInputStream music;
        final Bitmap first;
        final Bitmap last;
        Loaded(FileInputStream video, FileInputStream music, Bitmap first, Bitmap last) {
            this.video = video; this.music = music; this.first = first; this.last = last;
        }
        void closeDescriptors() { close(video); close(music); }
    }

    static Loaded load(Context context) throws Exception {
        JSONObject manifest;
        try (InputStream input = context.getAssets().open(MANIFEST)) {
            manifest = new JSONObject(new String(readBounded(input, 16384L), "UTF-8"));
        }
        if (manifest.getInt("schema") != 1) throw new IOException("manifest_schema");
        JSONObject pins = manifest.getJSONObject("assets");
        if (pins.length() != 4) throw new IOException("manifest_inventory");
        File external = context.getExternalFilesDir(null);
        if (external == null) throw new IOException("external_files_unavailable");
        File directory = new File(external, DIRECTORY);
        if (!directory.getCanonicalFile().getParentFile().equals(external.getCanonicalFile()))
            throw new IOException("asset_directory_escape");
        FileInputStream video = null, music = null;
        try {
            video = openVerified(directory, "reminder.mp4", pins, MAX_MEDIA_BYTES);
            music = openVerified(directory, "music.flac", pins, MAX_MEDIA_BYTES);
            Bitmap first = image(directory, "first-frame.png", pins);
            Bitmap last = image(directory, "last-frame.png", pins);
            if (first.getWidth() != last.getWidth() || first.getHeight() != last.getHeight())
                throw new IOException("frame_dimensions_differ");
            return new Loaded(video, music, first, last);
        } catch (Exception failed) {
            close(video); close(music); throw failed;
        }
    }

    private static FileInputStream openVerified(File directory, String name, JSONObject pins,
            long limit) throws Exception {
        JSONObject pin = pins.getJSONObject(name);
        long size = pin.getLong("bytes");
        String expected = pin.getString("sha256");
        if (size <= 0L || size > limit || !expected.matches("[a-f0-9]{64}"))
            throw new IOException("invalid_pin:" + name);
        File file = new File(directory, name);
        if (!file.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile())
                || !file.isFile() || file.length() != size) throw new IOException("asset_size_or_path:" + name);
        FileInputStream stream = new FileInputStream(file);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            long total = 0L;
            int n;
            while ((n = stream.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("asset_load_cancelled");
                total += n;
                if (total > size) throw new IOException("asset_grew:" + name);
                digest.update(buffer, 0, n);
            }
            if (total != size || !hex(digest.digest()).equals(expected))
                throw new IOException("asset_digest:" + name);
            stream.getChannel().position(0L);
            return stream;
        } catch (Exception failed) { close(stream); throw failed; }
    }

    private static Bitmap image(File directory, String name, JSONObject pins) throws Exception {
        byte[] data;
        try (FileInputStream stream = openVerified(directory, name, pins, MAX_IMAGE_BYTES)) {
            data = readBounded(stream, MAX_IMAGE_BYTES);
        }
        // Decode the same verified bytes, bounded before allocating the bitmap.
        if (!hex(MessageDigest.getInstance("SHA-256").digest(data))
                .equals(pins.getJSONObject(name).getString("sha256"))) throw new IOException("image_changed");
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth < 1 || bounds.outHeight < 1 || bounds.outWidth > 1920
                || bounds.outHeight > 1080) throw new IOException("image_dimensions:" + name);
        Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
        if (bitmap == null) throw new IOException("image_decode:" + name);
        return bitmap;
    }

    private static byte[] readBounded(InputStream stream, long maximum) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = stream.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("asset_load_cancelled");
            if ((long) bytes.size() + n > maximum) throw new IOException("asset_bound");
            bytes.write(buffer, 0, n);
        }
        return bytes.toByteArray();
    }

    private static String hex(byte[] bytes) {
        final char[] digits = "0123456789abcdef".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[i * 2] = digits[(bytes[i] & 255) >>> 4];
            out[i * 2 + 1] = digits[bytes[i] & 15];
        }
        return new String(out);
    }
    private static void close(InputStream stream) {
        if (stream != null) try { stream.close(); } catch (IOException ignored) { }
    }
}
