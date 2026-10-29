package com.shtong.gateway;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.stream.Stream;

final class ReferenceDataHttpClientFactory {
    private static final String CERTIFICATE_RESOURCE = "/certificates/cfca-ev-root.pem";
    private static final String EXPECTED_SHA256 =
            "5CC3D78E4E1D5E45547A04E6873E64F90CF9536D1CCC2EF800F355C4C5FD70FD";

    private ReferenceDataHttpClientFactory() {}

    static HttpClient create() {
        try {
            X509Certificate additionalRoot = bundledCertificate();
            verifyBundledCertificate(additionalRoot);

            KeyStore additionalRoots = KeyStore.getInstance(KeyStore.getDefaultType());
            additionalRoots.load(null, null);
            additionalRoots.setCertificateEntry("cfca-ev-root", additionalRoot);

            X509TrustManager defaultTrust = trustManager(null);
            X509TrustManager additionalTrust = trustManager(additionalRoots);
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{new CompositeTrustManager(defaultTrust, additionalTrust)}, null);
            return HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .sslContext(sslContext)
                    .build();
        } catch (GeneralSecurityException | IOException failure) {
            throw new IllegalStateException("Cannot initialize the administrative division TLS trust", failure);
        }
    }

    static X509Certificate bundledCertificate() throws IOException, CertificateException {
        InputStream stream = ReferenceDataHttpClientFactory.class.getResourceAsStream(CERTIFICATE_RESOURCE);
        if (stream == null) throw new IOException("Missing certificate resource " + CERTIFICATE_RESOURCE);
        try (stream) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(stream);
        }
    }

    static String sha256(X509Certificate certificate) throws GeneralSecurityException {
        return HexFormat.of().withUpperCase().formatHex(
                MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
    }

    private static void verifyBundledCertificate(X509Certificate certificate) throws GeneralSecurityException {
        if (!EXPECTED_SHA256.equals(sha256(certificate))) {
            throw new CertificateException("Unexpected CFCA root certificate fingerprint");
        }
        certificate.checkValidity();
        if (!certificate.getSubjectX500Principal().equals(certificate.getIssuerX500Principal())) {
            throw new CertificateException("The bundled CFCA certificate is not self-signed");
        }
        certificate.verify(certificate.getPublicKey());
    }

    private static X509TrustManager trustManager(KeyStore keyStore) throws GeneralSecurityException {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(keyStore);
        return Arrays.stream(factory.getTrustManagers())
                .filter(X509TrustManager.class::isInstance)
                .map(X509TrustManager.class::cast)
                .findFirst()
                .orElseThrow(() -> new GeneralSecurityException("No X509 trust manager available"));
    }

    private record CompositeTrustManager(X509TrustManager primary, X509TrustManager additional)
            implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            check(chain, authType, false);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            check(chain, authType, true);
        }

        private void check(X509Certificate[] chain, String authType, boolean server) throws CertificateException {
            try {
                if (server) primary.checkServerTrusted(chain, authType);
                else primary.checkClientTrusted(chain, authType);
                return;
            } catch (CertificateException primaryFailure) {
                try {
                    if (server) additional.checkServerTrusted(chain, authType);
                    else additional.checkClientTrusted(chain, authType);
                    return;
                } catch (CertificateException additionalFailure) {
                    primaryFailure.addSuppressed(additionalFailure);
                    throw primaryFailure;
                }
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return Stream.concat(Arrays.stream(primary.getAcceptedIssuers()),
                            Arrays.stream(additional.getAcceptedIssuers()))
                    .toArray(X509Certificate[]::new);
        }
    }
}
