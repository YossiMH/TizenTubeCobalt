package dev.yossi.onnkeyguard;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Small bounded HTTP parser for an authenticated loopback controller; no chunking or keep-alive. */
public final class ControllerProtocol {
    public enum Method { GET, POST }
    public static final int MAX_HEADER_BYTES = 4096;
    public static final int MAX_BODY_BYTES = 4096;
    public static final int MAX_REQUEST_BYTES = 8192;

    public static final class Request {
        public final Method method;
        public final String path;
        public final String authorization;
        public final byte[] body;

        private Request(Method method, String path, String authorization, byte[] body) {
            this.method = method;
            this.path = path;
            this.authorization = authorization;
            this.body = body;
        }
    }

    public static final class HttpError extends IOException {
        private static final long serialVersionUID = 1L;
        public final int status;

        public HttpError(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private ControllerProtocol() { }

    public static Request read(InputStream input, long deadlineNanos) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int tail = 0;
        while (tail != 0x0d0a0d0a) {
            checkDeadline(deadlineNanos);
            int value = input.read();
            if (value < 0) throw new HttpError(400, "incomplete_headers");
            header.write(value);
            if (header.size() > MAX_HEADER_BYTES) throw new HttpError(431, "headers_too_large");
            tail = (tail << 8) | value;
        }
        String[] lines = new String(header.toByteArray(), StandardCharsets.US_ASCII).split("\r\n");
        if (lines.length < 1 || lines.length > 49 || lines[0].length() > 1024) {
            throw new HttpError(400, "invalid_headers");
        }
        String[] first = lines[0].split(" ", -1);
        if (first.length != 3 || !("HTTP/1.1".equals(first[2]) || "HTTP/1.0".equals(first[2]))) {
            throw new HttpError(400, "invalid_request_line");
        }
        Method method;
        if ("GET".equals(first[0])) method = Method.GET;
        else if ("POST".equals(first[0])) method = Method.POST;
        else throw new HttpError(405, "unsupported_method");
        Map<String, String> headers = new HashMap<String, String>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.length() == 0) continue;
            if (line.length() > 1024 || Character.isWhitespace(line.charAt(0))) {
                throw new HttpError(400, "invalid_header");
            }
            int separator = line.indexOf(':');
            if (separator <= 0) throw new HttpError(400, "invalid_header");
            String key = line.substring(0, separator).toLowerCase(Locale.ROOT);
            if (!key.matches("[a-z0-9-]+") || headers.containsKey(key)) {
                throw new HttpError(400, "duplicate_or_invalid_header");
            }
            headers.put(key, line.substring(separator + 1).trim());
        }
        if (headers.containsKey("transfer-encoding")) throw new HttpError(400, "chunking_not_supported");
        int length = 0;
        String rawLength = headers.get("content-length");
        if (rawLength != null) {
            try {
                if (!rawLength.matches("[0-9]{1,6}")) throw new NumberFormatException();
                length = Integer.parseInt(rawLength);
            } catch (NumberFormatException invalid) {
                throw new HttpError(400, "invalid_content_length");
            }
        }
        if (length > MAX_BODY_BYTES || header.size() + length > MAX_REQUEST_BYTES) {
            throw new HttpError(413, "request_too_large");
        }
        if (method == Method.GET && length != 0) throw new HttpError(400, "get_body_not_supported");
        if (method == Method.POST) {
            String contentType = headers.get("content-type");
            if (rawLength == null || contentType == null
                    || !contentType.toLowerCase(Locale.ROOT).matches("application/json(?:\\s*;.*)?")) {
                throw new HttpError(400, "json_body_required");
            }
        }
        byte[] body = new byte[length];
        int offset = 0;
        while (offset < length) {
            checkDeadline(deadlineNanos);
            int read = input.read(body, offset, length - offset);
            if (read < 0) throw new HttpError(400, "incomplete_body");
            if (read == 0) continue;
            offset += read;
        }
        checkDeadline(deadlineNanos);
        return new Request(method, first[1], headers.get("authorization"), body);
    }

    public static boolean authorized(Request request, String token) {
        if (request == null || request.authorization == null || token == null) return false;
        return MessageDigest.isEqual(request.authorization.getBytes(StandardCharsets.UTF_8),
                ("Bearer " + token).getBytes(StandardCharsets.UTF_8));
    }

    private static void checkDeadline(long deadlineNanos) throws HttpError {
        if (System.nanoTime() - deadlineNanos >= 0) throw new HttpError(408, "request_timeout");
    }
}
