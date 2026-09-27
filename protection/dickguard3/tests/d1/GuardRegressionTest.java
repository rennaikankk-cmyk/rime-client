package d1;

import com.dickclient.DickClient;

public final class GuardRegressionTest {
    public static void main(String[] args) {
        new G().onInitialize();
        new G().onInitializeClient();
        require(!G.isAuthorized(), "missing guard lease must deny ongoing actions");
        require(E.state == E.DENIED, "missing credential must deny startup");
        require(!DickClient.initialized, "original client must not start before authorization");
        require(!V.spotCheck(), "unverifiable mod must fail integrity checking");
        require(!V.verifyNow(), "unverifiable mod must fail the full integrity check");
        require(E.integrityUnavailable, "failed integrity checking must be recorded");
        require(!E.tampered, "an I/O failure must not be reported as proven tampering");

        E.token = "a\"b\\c\n";
        E.envNote = "agent\rtest";
        String body = B.requestBody("hwid", "nonce", "session");
        require(body.contains("\"token\":\"a\\\"b\\\\c\\u000a\""), "token must be JSON escaped");
        require(body.contains("\"env\":\"agent\\u000dtest\""), "environment note must be JSON escaped");
        require(E.hex(16).matches("[0-9a-f]{32}"), "nonce must be 16 random bytes in hex");
        require(E.unhex("abc") == null, "odd-length hex must be rejected");
        E.state = E.OK;
        E.integrityUnavailable = false;
        V.lastResult = true;
        E.lastOkMs = System.currentTimeMillis();
        E.graceMs = 10000;
        require(G.isAuthorized(), "valid signed state permits ongoing actions");
        E.lastOkMs -= 20000;
        require(!G.isAuthorized(), "elapsed guard allowance stops ongoing actions");
        E.lastOkMs = System.currentTimeMillis();
        E.deny("revoked");
        E.netFail();
        require(!G.isAuthorized(), "network failure cannot restore explicit revocation");
        System.out.println("GuardRegressionTest passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
