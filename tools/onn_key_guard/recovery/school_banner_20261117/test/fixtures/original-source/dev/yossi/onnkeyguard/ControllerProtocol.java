package dev.yossi.onnkeyguard;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;

/* JADX INFO: loaded from: classes.dex */
public final class ControllerProtocol {
    public static final int MAX_BODY_BYTES = 4096;
    public static final int MAX_HEADER_BYTES = 4096;
    public static final int MAX_REQUEST_BYTES = 8192;

    public enum Method {
        GET,
        POST
    }

    public static final class Request {
        public final String authorization;
        public final byte[] body;
        public final Method method;
        public final String path;

        private Request(Method method, String str, String str2, byte[] bArr) {
            this.method = method;
            this.path = str;
            this.authorization = str2;
            this.body = bArr;
        }
    }

    public static final class HttpError extends IOException {
        private static final long serialVersionUID = 1;
        public final int status;

        public HttpError(int i, String str) {
            super(str);
            this.status = i;
        }
    }

    private ControllerProtocol() {
    }

    public static Request read(InputStream inputStream, long j) throws IOException {
        Method method;
        int i;
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        int i2 = 0;
        int i3 = 0;
        while (i3 != 218762506) {
            checkDeadline(j);
            int i4 = inputStream.read();
            if (i4 < 0) {
                throw new HttpError(400, "incomplete_headers");
            }
            byteArrayOutputStream.write(i4);
            if (byteArrayOutputStream.size() > 4096) {
                throw new HttpError(431, "headers_too_large");
            }
            i3 = (i3 << 8) | i4;
        }
        String[] strArrSplit = new String(byteArrayOutputStream.toByteArray(), StandardCharsets.US_ASCII).split("\r\n");
        if (strArrSplit.length < 1 || strArrSplit.length > 49 || strArrSplit[0].length() > 1024) {
            throw new HttpError(400, "invalid_headers");
        }
        String[] strArrSplit2 = strArrSplit[0].split(" ", -1);
        if (strArrSplit2.length != 3 || (!"HTTP/1.1".equals(strArrSplit2[2]) && !"HTTP/1.0".equals(strArrSplit2[2]))) {
            throw new HttpError(400, "invalid_request_line");
        }
        if ("GET".equals(strArrSplit2[0])) {
            method = Method.GET;
        } else {
            if (!"POST".equals(strArrSplit2[0])) {
                throw new HttpError(405, "unsupported_method");
            }
            method = Method.POST;
        }
        HashMap map = new HashMap();
        for (int i5 = 1; i5 < strArrSplit.length; i5++) {
            String str = strArrSplit[i5];
            if (str.length() != 0) {
                if (str.length() > 1024 || Character.isWhitespace(str.charAt(0))) {
                    throw new HttpError(400, "invalid_header");
                }
                int iIndexOf = str.indexOf(58);
                if (iIndexOf <= 0) {
                    throw new HttpError(400, "invalid_header");
                }
                String lowerCase = str.substring(0, iIndexOf).toLowerCase(Locale.ROOT);
                if (!lowerCase.matches("[a-z0-9-]+") || map.containsKey(lowerCase)) {
                    throw new HttpError(400, "duplicate_or_invalid_header");
                }
                map.put(lowerCase, str.substring(iIndexOf + 1).trim());
            }
        }
        if (map.containsKey("transfer-encoding")) {
            throw new HttpError(400, "chunking_not_supported");
        }
        String str2 = (String) map.get("content-length");
        if (str2 == null) {
            i = 0;
        } else {
            try {
                if (!str2.matches("[0-9]{1,6}")) {
                    throw new NumberFormatException();
                }
                i = Integer.parseInt(str2);
            } catch (NumberFormatException e) {
                throw new HttpError(400, "invalid_content_length");
            }
        }
        if (i > 4096 || byteArrayOutputStream.size() + i > 8192) {
            throw new HttpError(413, "request_too_large");
        }
        if (method == Method.GET && i != 0) {
            throw new HttpError(400, "get_body_not_supported");
        }
        if (method == Method.POST) {
            String str3 = (String) map.get("content-type");
            if (str2 == null || str3 == null || !str3.toLowerCase(Locale.ROOT).matches("application/json(?:\\s*;.*)?")) {
                throw new HttpError(400, "json_body_required");
            }
        }
        byte[] bArr = new byte[i];
        while (i2 < i) {
            checkDeadline(j);
            int i6 = inputStream.read(bArr, i2, i - i2);
            if (i6 < 0) {
                throw new HttpError(400, "incomplete_body");
            }
            if (i6 != 0) {
                i2 += i6;
            }
        }
        checkDeadline(j);
        return new Request(method, strArrSplit2[1], (String) map.get("authorization"), bArr);
    }

    public static boolean authorized(Request request, String str) {
        if (request == null || request.authorization == null || str == null) {
            return false;
        }
        return MessageDigest.isEqual(request.authorization.getBytes(StandardCharsets.UTF_8), ("Bearer " + str).getBytes(StandardCharsets.UTF_8));
    }

    private static void checkDeadline(long j) throws HttpError {
        if (System.nanoTime() - j >= 0) {
            throw new HttpError(408, "request_timeout");
        }
    }
}
