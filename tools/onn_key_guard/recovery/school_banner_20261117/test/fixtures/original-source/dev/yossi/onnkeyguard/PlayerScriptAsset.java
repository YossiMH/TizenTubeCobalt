package dev.yossi.onnkeyguard;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/* JADX INFO: loaded from: classes.dex */
public final class PlayerScriptAsset {
    public static final int MAX_SCRIPT_BYTES = 1048576;
    private final byte[] headers;
    private final String path;
    private final byte[] script;
    private final String sha256;

    private PlayerScriptAsset(byte[] bArr, String str) {
        this.script = bArr;
        this.sha256 = str;
        this.path = "/" + str.substring(0, 41) + ".js";
        this.headers = ("HTTP/1.1 200 OK\r\nContent-Type: application/javascript; charset=utf-8\r\nContent-Length: " + bArr.length + "\r\nETag: \"" + str + "\"\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
    }

    public static PlayerScriptAsset load(InputStream inputStream, InputStream inputStream2) throws IOException {
        try {
            try {
                if (inputStream == null || inputStream2 == null) {
                    throw new IOException("missing_player_asset");
                }
                byte[] bounded = readBounded(inputStream, MAX_SCRIPT_BYTES);
                if (bounded.length == 0) {
                    throw new IOException("empty_player_asset");
                }
                String strTrim = new String(readBounded(inputStream2, 66), StandardCharsets.US_ASCII).trim();
                if (!strTrim.matches("[0-9a-f]{64}")) {
                    throw new IOException("invalid_player_digest");
                }
                String strDigest = digest(bounded);
                if (!MessageDigest.isEqual(strTrim.getBytes(StandardCharsets.US_ASCII), strDigest.getBytes(StandardCharsets.US_ASCII))) {
                    throw new IOException("player_digest_mismatch");
                }
                PlayerScriptAsset playerScriptAsset = new PlayerScriptAsset(bounded, strDigest);
                if (inputStream2 != null) {
                    inputStream2.close();
                }
                if (inputStream != null) {
                    inputStream.close();
                }
                return playerScriptAsset;
            } catch (Throwable th) {
                if (inputStream2 != null) {
                    try {
                        inputStream2.close();
                    } catch (Throwable th2) {
                        th.addSuppressed(th2);
                    }
                }
                throw th;
            }
        } catch (Throwable th3) {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (Throwable th4) {
                    th3.addSuppressed(th4);
                }
            }
            throw th3;
        }
    }

    public String requestPath() {
        return this.path;
    }

    public String sha256() {
        return this.sha256;
    }

    public boolean writeResponse(ControllerProtocol.Request request, OutputStream outputStream) throws IOException {
        if (request == null || request.method != ControllerProtocol.Method.GET || !matches(request.path)) {
            return false;
        }
        outputStream.write(this.headers);
        outputStream.write(this.script);
        outputStream.flush();
        return true;
    }

    private boolean matches(String str) {
        if (this.path.equals(str)) {
            return true;
        }
        if (str == null || !str.startsWith(this.path + "?v=")) {
            return false;
        }
        return str.substring(this.path.length() + 3).matches("[0-9]{1,20}(?:\\.[0-9]{1,6})?");
    }

    private static byte[] readBounded(InputStream inputStream, int i) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream(Math.min(i, ControllerProtocol.MAX_REQUEST_BYTES));
        byte[] bArr = new byte[ControllerProtocol.MAX_REQUEST_BYTES];
        while (true) {
            int i2 = inputStream.read(bArr);
            if (i2 != -1) {
                if (i2 != 0) {
                    if (i2 > i - byteArrayOutputStream.size()) {
                        throw new IOException("player_asset_too_large");
                    }
                    byteArrayOutputStream.write(bArr, 0, i2);
                }
            } else {
                return byteArrayOutputStream.toByteArray();
            }
        }
    }

    private static String digest(byte[] bArr) throws IOException {
        try {
            byte[] bArrDigest = MessageDigest.getInstance("SHA-256").digest(bArr);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : bArrDigest) {
                sb.append(String.format(Locale.ROOT, "%02x", Integer.valueOf(b & 255)));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("sha256_unavailable", e);
        }
    }
}
