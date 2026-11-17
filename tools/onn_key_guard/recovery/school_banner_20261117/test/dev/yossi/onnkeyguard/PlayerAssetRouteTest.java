package dev.yossi.onnkeyguard;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Host regression for the original asset class with the one exact constructor-path delta. */
public final class PlayerAssetRouteTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        String legacyDigest = args[0];
        String legacyPath = "/" + legacyDigest.substring(0, 41) + ".js";
        byte[] script = "window.__schoolBannerRouteTest = true;\n".getBytes(StandardCharsets.UTF_8);
        String digest = hex(MessageDigest.getInstance("SHA-256").digest(script));
        PlayerScriptAsset asset = load(script, digest);
        check(legacyPath.equals(asset.requestPath()), "installed exact41 route remains stable with new content");
        check(digest.equals(asset.sha256()), "content digest remains actual new content hash");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        check(asset.writeResponse(request("GET", legacyPath), output), "installed GET route still serves asset");
        String response = new String(output.toByteArray(), StandardCharsets.UTF_8);
        check(response.contains("ETag: \"" + digest + "\""), "ETag identifies actual new script");
        check(response.endsWith(new String(script, StandardCharsets.UTF_8)), "response body is verified new script");
        check(response.contains("Content-Length: " + script.length + "\r\n"), "actual response length retained");
        for (String suffix : new String[]{"?v=1", "?v=12345678901234567890.123456"})
            check(asset.writeResponse(request("GET", legacyPath + suffix), new ByteArrayOutputStream()), "bounded cache-buster accepted " + suffix);
        for (String suffix : new String[]{"?v=", "?v=1&x=2", "?v=123456789012345678901", "?v=1.1234567", "#fragment"})
            check(!asset.writeResponse(request("GET", legacyPath + suffix), new ByteArrayOutputStream()), "unexpected route rejected " + suffix);
        check(!asset.writeResponse(request("GET", "/" + digest.substring(0, 41) + ".js"), new ByteArrayOutputStream()), "no accidental extra content-derived route");
        check(!asset.writeResponse(request("POST", legacyPath), new ByteArrayOutputStream()), "GET-only asset serving preserved");
        check(!asset.writeResponse(null, new ByteArrayOutputStream()), "null request rejected");
        expectFailure(script, legacyDigest, "mismatched digest rejected");
        expectFailure(new byte[0], digest, "empty script rejected");
        expectFailure(script, "bad", "malformed digest rejected");
        System.out.println("PlayerAssetRoute: " + checks + " behavioral checks passed");
    }

    private static PlayerScriptAsset load(byte[] script, String digest) throws IOException {
        return PlayerScriptAsset.load(new ByteArrayInputStream(script),
                new ByteArrayInputStream((digest + "\n").getBytes(StandardCharsets.US_ASCII)));
    }

    private static ControllerProtocol.Request request(String method, String path) throws IOException {
        String headers = method + " " + path + " HTTP/1.1\r\nHost: 127.0.0.1\r\n";
        if ("POST".equals(method)) headers += "Content-Length: 0\r\nContent-Type: application/json\r\n";
        return ControllerProtocol.read(new ByteArrayInputStream((headers + "\r\n").getBytes(StandardCharsets.US_ASCII)),
                System.nanoTime() + 1000000000L);
    }

    private static void expectFailure(byte[] script, String digest, String message) throws Exception {
        boolean failed = false;
        try { load(script, digest); }
        catch (IOException expected) { failed = true; }
        check(failed, message);
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte value : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return out.toString();
    }

    private static void check(boolean truth, String message) {
        checks++;
        if (!truth) throw new AssertionError(message);
    }
}
