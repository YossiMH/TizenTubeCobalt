package dev.yossi.onnkeyguard;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Exercises actual parser, source classifier, and paired-key gate; no Android substitutes. */
public final class RuntimeProtocolTest {
    private static int assertions;
    private static final String TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    public static void main(String[] args) throws Exception {
        classifyObservedDevices();
        balancedPhysicalEvents();
        parseValidRequests();
        rejectMalformedAndOversizedRequests();
        System.out.println("PASS RuntimeProtocolTest: " + assertions + " assertions");
    }

    private static void classifyObservedDevices() {
        equal(RemoteIdentity.Origin.PHYSICAL_REMOTE, RemoteIdentity.classify(7,
                "Onn-Remote Keyboard", 0x0957, 0x0031, RemoteIdentity.PHYSICAL_DESCRIPTOR), "physical identity");
        equal(RemoteIdentity.Origin.SOFTWARE_REMOTE, RemoteIdentity.classify(42,
                "virtual-remote", 0x18d1, 0x0100, RemoteIdentity.PHONE_DESCRIPTOR), "phone positive device ID");
        equal(RemoteIdentity.Origin.ASSISTANT, RemoteIdentity.classify(-1, "", 0, 0, ""), "injected identity");
        equal(RemoteIdentity.Origin.UNKNOWN, RemoteIdentity.classify(0, "", 0, 0, ""), "zero ID not presumed phone");
        equal(RemoteIdentity.Origin.UNKNOWN, RemoteIdentity.classify(9,
                "virtual-remote", 0x18d1, 0x0100, "different"), "unknown descriptor");
        equal(RemoteIdentity.Origin.UNKNOWN, RemoteIdentity.classify(9,
                "Onn-Remote Keyboard", 0x18d1, 0x0100, RemoteIdentity.PHONE_DESCRIPTOR), "name alone insufficient");
        equal(RemoteIdentity.Origin.UNKNOWN, RemoteIdentity.classify(9,
                "virtual-remote", 0x0957, 0x0031, RemoteIdentity.PHYSICAL_DESCRIPTOR), "physical vendor alone insufficient");
    }

    private static void balancedPhysicalEvents() {
        PhysicalKeyGate gate = new PhysicalKeyGate();
        RemoteIdentity.Origin physical = RemoteIdentity.Origin.PHYSICAL_REMOTE;
        PhysicalKeyGate.Protection enabled = PhysicalKeyGate.Protection.ENABLED;
        PhysicalKeyGate.Protection disabled = PhysicalKeyGate.Protection.DISABLED;
        truth(gate.onEvent(0, 7, 3, physical, enabled), "physical HOME down blocked");
        truth(gate.onEvent(0, 7, 3, physical, disabled), "held repeat stays blocked after disable");
        truth(gate.onEvent(1, 7, 3, physical, disabled), "matching release remains consumed after disable");
        truth(!gate.onEvent(0, 7, 3, physical, disabled), "next press passes after disable");
        truth(!gate.onEvent(1, 7, 3, physical, enabled), "unblocked down has unblocked up even if session starts");
        for (int key : new int[] {24, 25, 91, 164}) {
            truth(!gate.onEvent(0, 7, key, physical, enabled), "volume down passes " + key);
            truth(!gate.onEvent(1, 7, key, physical, enabled), "volume up passes " + key);
        }
        for (RemoteIdentity.Origin origin : new RemoteIdentity.Origin[] {RemoteIdentity.Origin.SOFTWARE_REMOTE,
                RemoteIdentity.Origin.ASSISTANT, RemoteIdentity.Origin.UNKNOWN}) {
            truth(!gate.onEvent(0, 10, 3, origin, enabled), "nonphysical down passes " + origin);
            truth(!gate.onEvent(1, 10, 3, origin, enabled), "nonphysical up passes " + origin);
        }
        truth(gate.onEvent(0, 7, 4, physical, enabled), "physical back captured");
        truth(!gate.onEvent(1, 8, 4, physical, enabled), "different device release not swallowed");
        truth(gate.onEvent(1, 7, 4, RemoteIdentity.Origin.UNKNOWN, disabled), "original pair retained despite source disappearance");
        truth(gate.onEvent(2, 7, 3, physical, enabled), "multiple physical event blocked");
        truth(!gate.onEvent(2, 7, 25, physical, enabled), "multiple volume event passes");
        gate.onEvent(0, 7, 4, physical, enabled);
        gate.clear();
        truth(!gate.onEvent(1, 7, 4, physical, disabled), "teardown removes old ownership");
    }

    private static void parseValidRequests() throws Exception {
        ControllerProtocol.Request get = read("GET /status HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer "
                + TOKEN + "\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n");
        equal(ControllerProtocol.Method.GET, get.method, "GET method");
        equal("/status", get.path, "exact status path");
        equal(0, get.body.length, "GET empty body");
        truth(ControllerProtocol.authorized(get, TOKEN), "correct bearer accepted");
        truth(!ControllerProtocol.authorized(get, TOKEN.substring(1)), "wrong token rejected");
        truth(!ControllerProtocol.authorized(get, null), "missing configured token rejected");
        String body = "{\"type\":\"native_health\",\"status\":\"stopped\"}";
        ControllerProtocol.Request post = read(post(body));
        equal(ControllerProtocol.Method.POST, post.method, "POST method");
        equal("/event", post.path, "exact event path");
        equal(body, new String(post.body, StandardCharsets.UTF_8), "JSON body copied exactly");
        truth(!ControllerProtocol.authorized(read("GET /status HTTP/1.0\r\n\r\n"), TOKEN), "missing authorization rejected");
        String maxBody = repeat('a', ControllerProtocol.MAX_BODY_BYTES);
        equal(4096, read(post(maxBody)).body.length, "exact maximum body accepted");
    }

    private static void rejectMalformedAndOversizedRequests() throws Exception {
        invalid("GET /status HTTP/2\r\n\r\n", 400);
        invalid("PUT /event HTTP/1.1\r\n\r\n", 405);
        invalid("GET  /status HTTP/1.1\r\n\r\n", 400);
        invalid("GET /status HTTP/1.1\r\n Host: invalid\r\n\r\n", 400);
        invalid("GET /status HTTP/1.1\r\nAuthorization: one\r\nauthorization: two\r\n\r\n", 400);
        invalid("POST /event HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n", 400);
        invalid("POST /event HTTP/1.1\r\nContent-Type: application/json\r\nContent-Length: -1\r\n\r\n", 400);
        invalid("POST /event HTTP/1.1\r\nContent-Type: application/json\r\nContent-Length: 1.5\r\n\r\n", 400);
        invalid("POST /event HTTP/1.1\r\nContent-Length: 1\r\n\r\na", 400);
        invalid("POST /event HTTP/1.1\r\nContent-Type: text/plain\r\nContent-Length: 1\r\n\r\na", 400);
        invalid("GET /status HTTP/1.1\r\nContent-Length: 1\r\n\r\na", 400);
        invalid(post(repeat('a', 4097)), 413);
        invalid("POST /event HTTP/1.1\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\na", 400);
        invalid("GET /status HTTP/1.1\r\nX-Fill: " + repeat('a', 4100) + "\r\n\r\n", 431);
        invalid("GET /status HTTP/1.1\n\n", 400);
        try {
            ControllerProtocol.read(new ByteArrayInputStream("GET /status HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII)),
                    System.nanoTime() - 1);
            throw new AssertionError("expired read accepted");
        } catch (ControllerProtocol.HttpError expired) {
            equal(408, expired.status, "absolute request deadline enforced");
        }
    }

    private static ControllerProtocol.Request read(String request) throws IOException {
        return ControllerProtocol.read(new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8)),
                System.nanoTime() + TimeUnit.SECONDS.toNanos(1));
    }

    private static String post(String body) {
        return "POST /event HTTP/1.1\r\nAuthorization: Bearer " + TOKEN
                + "\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: "
                + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body;
    }

    private static String repeat(char c, int count) {
        char[] data = new char[count];
        java.util.Arrays.fill(data, c);
        return new String(data);
    }

    private static void invalid(String request, int expectedStatus) throws IOException {
        try {
            read(request);
            throw new AssertionError("invalid request accepted");
        } catch (ControllerProtocol.HttpError error) {
            equal(expectedStatus, error.status, "HTTP rejection status");
        }
    }

    private static void truth(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }

    private static void equal(Object expected, Object actual, String label) {
        assertions++;
        if (!expected.equals(actual)) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
    }
}
