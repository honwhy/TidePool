package io.ftppool.core;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import java.util.Objects;

/**
 * TLS configuration for FTPS connections (spec section 64).
 *
 * <p>Carries only JDK security types so core stays dependency-free. The adapter
 * interprets this config when building a physical {@code FTPSClient}.</p>
 *
 * <p>Trust verification is <strong>enabled by default</strong> — hostname
 * verification and server certificates are not silently disabled. The only way
 * to skip verification is the explicit, clearly-named
 * {@link #insecureTrustAll()} factory, which is intended for local development
 * and test servers only.</p>
 *
 * <p>Precedence when building a context: explicit {@link #sslContext()} wins;
 * otherwise {@link #trustManagers()}/{@link #keyManagers()} build a fresh
 * {@code TLS} context; otherwise the JVM default context is used.</p>
 */
public record FtpSslConfig(
        SSLContext sslContext,
        TrustManager[] trustManagers,
        KeyManager[] keyManagers,
        boolean trustAll,
        boolean hostnameVerification) {

    /** Secure defaults: JVM trust store, hostname verification ON, no trust-all. */
    public static FtpSslConfig secureDefaults() {
        return new FtpSslConfig(null, null, null, false, true);
    }

    /**
     * UNSAFE — development/testing only. Trusts any server certificate and
     * skips hostname verification. Never use against production servers; the
     * adapter logs a loud warning when this is applied.
     */
    public static FtpSslConfig insecureTrustAll() {
        return new FtpSslConfig(null, null, null, true, false);
    }

    public FtpSslConfig {
        if (trustAll) {
            hostnameVerification = false;
        }
    }

    public FtpSslConfig withSslContext(SSLContext context) {
        return new FtpSslConfig(context, trustManagers, keyManagers, trustAll, hostnameVerification);
    }

    @Override
    public String toString() {
        return "FtpSslConfig["
                + "sslContext=" + (sslContext == null ? "default" : "custom")
                + ", trustManagers=" + (trustManagers == null ? "default" : "custom")
                + ", keyManagers=" + (keyManagers == null ? "none" : "custom")
                + ", trustAll=" + trustAll
                + ", hostnameVerification=" + hostnameVerification
                + ']';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FtpSslConfig that)) {
            return false;
        }
        return trustAll == that.trustAll
                && hostnameVerification == that.hostnameVerification
                && Objects.equals(sslContext, that.sslContext)
                && java.util.Arrays.equals(trustManagers, that.trustManagers)
                && java.util.Arrays.equals(keyManagers, that.keyManagers);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(sslContext, trustAll, hostnameVerification);
        result = 31 * result + java.util.Arrays.hashCode(trustManagers);
        result = 31 * result + java.util.Arrays.hashCode(keyManagers);
        return result;
    }
}