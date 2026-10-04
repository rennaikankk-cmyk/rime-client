package dev.guizhou.util.license;

import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;

/** Dedicated trust store for the existing IP auth host, obtained through the owner's SSH connection.
 * No system trust override, trust-all manager, redirect, or plaintext fallback. Certificate
 * rotation requires shipping overlapping trusted certificates before changing the server.
 */
public final class AuthTransport {
    private AuthTransport() {}
    private static final String SERVER_CERTIFICATE = """
            -----BEGIN CERTIFICATE-----
            MIIC1zCCAb+gAwIBAgIGAaCvRaG/MA0GCSqGSIb3DQEBCwUAMBsxGTAXBgNVBAMT
            EGd1aXpob3V3YXJlLWF1dGgwHhcNMjYwOTE2MTIwOTMxWhcNMzYwOTE0MTIwOTMx
            WjAbMRkwFwYDVQQDExBndWl6aG91d2FyZS1hdXRoMIIBIjANBgkqhkiG9w0BAQEF
            AAOCAQ8AMIIBCgKCAQEAsqu6zjGELOIxrIWRxQadnrWNYxGtxYRQTem4yzkmO2Hk
            XVdVT9hD9rW6mYyYMngo9OE3GlNh1dC4hEYQmIaHkbzrt/hfaFN5GCpb7CDZNKrW
            V6bKBwY70i8LzTFFhInZs3NpKykgqpvtLk6RxGJZnhGw7xJ0OJ08H4wrjwPxxf7c
            nVtdeCuUf9/nbFy8OYZlZ6tuhAAgyBFf6CHGFTs5850OOMbU5zGgg8NhcBgHeGAi
            vFW197vIrdXx30FcZDhybPA9/8/GN2oLhogIbUhROiJrTeZInEQz9zKOiMpg8CiS
            NqZYzhFwpPTVw5A53J68ykcfnNgoL/ZL00ljy0b2UwIDAQABoyEwHzAMBgNVHRME
            BTADAQH/MA8GA1UdEQQIMAaHBD7qdeIwDQYJKoZIhvcNAQELBQADggEBAH1AKv9n
            Tg5RybS5Nos++jVKA5GJpxYfIqGqvs76D+FbFb65Jzect9teM5jaax0pU0K0H6ZT
            D2rLOFutmZa7ESnASUKU9Sb9e1YkRtNuw1GyKQy0mFt5oPwKivimY9V+duZaTPWF
            1iv7CKJ8ow9KgtWk2o+1BDlwIJYO6+fj3bAW2GEqAnm8s0fytOTfcArgTL9Jn3Pg
            mbO0PUDizvkNGG54JP1XIU+b8rOgPe6qUR94nNunQwSaphwg7ayvSeeam7MBw1u+
            TKA1HcMIy+vYIj7NYQrjz6lY0J8fRfIskQ1L9hudugQSz88B/bcaGMkZB7DU/MsY
            j+EvVBR5apHzn8E=
            -----END CERTIFICATE-----
            """;
    public static HttpClient create() {
        try {
            var certificate = CertificateFactory.getInstance("X.509").generateCertificate(
                    new ByteArrayInputStream(SERVER_CERTIFICATE.getBytes(StandardCharsets.US_ASCII)));
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            store.setCertificateEntry("guizhouware-auth", certificate);
            TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(store);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trust.getTrustManagers(), null);
            SSLParameters parameters = new SSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            return HttpClient.newBuilder().sslContext(context).sslParameters(parameters)
                    .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
        } catch (Exception e) { throw new IllegalStateException("Invalid bundled auth certificate", e); }
    }

    /** The website uses a public CA chain; retain ordinary HTTPS hostname verification.
     * Its authorization responses are also verified against the pinned Ed25519 public key.
     * Keep the IP-only certificate store above for the existing cloud-config endpoint.
     */
    public static HttpClient createPublicCa() {
        try {
            TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init((KeyStore) null);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trust.getTrustManagers(), null);
            SSLParameters parameters = new SSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            return HttpClient.newBuilder().sslContext(context).sslParameters(parameters)
                    .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
        } catch (Exception e) { throw new IllegalStateException("Cannot initialize website TLS trust", e); }
    }
}
