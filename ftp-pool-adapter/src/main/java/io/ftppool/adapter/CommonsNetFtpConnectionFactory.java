package io.ftppool.adapter;

import io.ftppool.api.FtpConnection;
import io.ftppool.api.FtpConnectionId;
import io.ftppool.api.FtpException;
import io.ftppool.api.FtpExceptionType;
import io.ftppool.api.FtpProtocol;
import io.ftppool.core.FtpConnectionFactory;
import io.ftppool.core.FtpConnectionFactoryProvider;
import io.ftppool.core.FtpConnectionSettings;
import io.ftppool.core.FtpSslConfig;
import io.ftppool.core.PoolConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.FTPSClient;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Apache Commons Net {@link FtpConnectionFactory}.
 *
 * <p>Connection creation (spec section 21): connect → login → BINARY → passive →
 * FTPS data protection → record initial directory. Any step failing destroys the
 * physical client and surfaces a classified {@link FtpException}.</p>
 *
 * <p>Also registers as {@link FtpConnectionFactoryProvider} ("commons-net") so
 * {@code FtpPoolBuilder} can discover it via {@code META-INF/services}.</p>
 */
@Slf4j
public final class CommonsNetFtpConnectionFactory implements FtpConnectionFactory, FtpConnectionFactoryProvider {

    /** SPI registration name (see {@code META-INF/services/io.ftppool.core.FtpConnectionFactoryProvider}). */
    public static final String PROVIDER_NAME = "commons-net";

    private final FtpConnectionSettings settings;
    private final PoolConfiguration config;
    private final AtomicLong idSequence = new AtomicLong();

    public CommonsNetFtpConnectionFactory(FtpConnectionSettings settings, PoolConfiguration config) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * ServiceLoader entry point (spec's SPI registration). The bare instance is
     * only ever used through the {@link FtpConnectionFactoryProvider} methods
     * ({@link #supports} / {@link #create}); it must never act as a real
     * connection factory itself.
     */
    public CommonsNetFtpConnectionFactory() {
        this.settings = null;
        this.config = null;
    }

    // ------------------------- FtpConnectionFactoryProvider -------------------------

    @Override
    public String name() {
        return PROVIDER_NAME;
    }

    @Override
    public boolean supports(FtpProtocol protocol) {
        return protocol == FtpProtocol.FTP
                || protocol == FtpProtocol.FTPS_EXPLICIT
                || protocol == FtpProtocol.FTPS_IMPLICIT;
    }

    @Override
    public FtpConnectionFactory create(FtpConnectionSettings request, PoolConfiguration requestConfig) {
        return new CommonsNetFtpConnectionFactory(request, requestConfig == null ? config : requestConfig);
    }

    // ------------------------- ResourceFactory / FtpConnectionFactory -------------------------

    @Override
    public FtpConnection create() throws FtpException {
        requireWired();
        FTPClient client = newClient(settings.protocol(), settings.ssl());
        try {
            client.setControlEncoding(settings.encoding());
            client.setConnectTimeout(Math.toIntExact(config.getConnectionTimeout().toMillis()));
            client.setDefaultTimeout(Math.toIntExact(config.getSocketTimeout().toMillis()));
            client.setDataTimeout(Math.toIntExact(config.getDataTimeout().toMillis()));
            connectAndLogin(client);
            String initialDirectory = client.printWorkingDirectory();
            return new CommonsNetFtpConnection(
                    new FtpConnectionId(idSequence.incrementAndGet()), client, initialDirectory);
        } catch (IOException e) {
            quietlyDisconnect(client);
            throw FtpExceptionTranslator.connection("Connect to " + settings.host() + " failed", e);
        } catch (FtpException e) {
            quietlyDisconnect(client);
            throw e;
        } catch (RuntimeException e) {
            quietlyDisconnect(client);
            throw e;
        }
    }

    @Override
    public boolean validate(FtpConnection connection) {
        requireWired();
        if (connection == null || connection.isBroken()) {
            return false;
        }
        if (connection instanceof CommonsNetFtpConnection cc) {
            return cc.isValid();
        }
        return false;
    }

    @Override
    public boolean reset(FtpConnection connection) {
        requireWired();
        if (connection == null || connection.isBroken()) {
            return false;
        }
        if (connection instanceof CommonsNetFtpConnection cc) {
            try {
                cc.resetState();
                return true;
            } catch (FtpException e) {
                log.debug("State reset failed for {}: {}", cc.id(), e.getType());
                cc.markBroken();
                return false;
            }
        }
        return false;
    }

    @Override
    public void destroy(FtpConnection connection) {
        requireWired();
        if (connection instanceof CommonsNetFtpConnection cc) {
            quietlyDisconnect(cc.client());
        }
    }

    /** Guard against accidental use of the bare ServiceLoader entry point as a real factory. */
    private void requireWired() {
        if (settings == null || config == null) {
            throw new IllegalStateException(
                    "CommonsNetFtpConnectionFactory loaded via ServiceLoader has no settings; "
                            + "call create(settings, config) on the provider instead.");
        }
    }

    // ------------------------- internals -------------------------

    private static FTPClient newClient(FtpProtocol protocol, FtpSslConfig ssl) {
        if (protocol == FtpProtocol.FTP) {
            return new FTPClient();
        }
        boolean implicit = protocol == FtpProtocol.FTPS_IMPLICIT;
        FtpSslConfig policy = ssl == null ? FtpSslConfig.secureDefaults() : ssl;
        FTPSClient client = policy.trustAll()
                ? new FTPSClient(implicit, trustAllContext())
                : new FTPSClient(implicit, resolveContext(policy));
        configureTls(client, policy);
        return client;
    }

    /**
     * Applies the TLS policy from spec section 64. Hostname verification is on
     * by default; {@code trust-all} must be requested explicitly and logs a
     * loud unsafe-development warning. Never disables verification silently.
     */
    static void configureTls(FTPSClient client, FtpSslConfig ssl) {
        Objects.requireNonNull(client, "client");
        FtpSslConfig policy = ssl == null ? FtpSslConfig.secureDefaults() : ssl;
        if (policy.trustAll()) {
            log.warn("FTPS trust-all enabled — server certificate AND hostname verification disabled. "
                    + "UNSAFE, development/testing only; never use against production servers.");
            client.setTrustManager(TrustAllManager.INSTANCE);
            client.setHostnameVerifier((host, session) -> true);
            client.setEndpointCheckingEnabled(false);
            return;
        }
        if (policy.hostnameVerification()) {
            client.setEndpointCheckingEnabled(true);
        }
    }

    /**
     * Resolves the {@link SSLContext} for a policy: the explicit context wins,
     * else a fresh {@code TLS} context built from the configured trust/key
     * managers, else the JVM default context.
     */
    static SSLContext resolveContext(FtpSslConfig ssl) {
        try {
            FtpSslConfig policy = ssl == null ? FtpSslConfig.secureDefaults() : ssl;
            if (policy.sslContext() != null) {
                return policy.sslContext();
            }
            if (policy.trustManagers() != null || policy.keyManagers() != null) {
                return buildContext(policy.trustManagers(), policy.keyManagers());
            }
            return SSLContext.getDefault();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to build FTPS TLS context", e);
        }
    }

    /** Context trusting every certificate — only for the explicit unsafe path. */
    static SSLContext trustAllContext() {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{TrustAllManager.INSTANCE}, null);
            return context;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to build trust-all TLS context", e);
        }
    }

    private static SSLContext buildContext(TrustManager[] trustManagers, javax.net.ssl.KeyManager[] keyManagers)
            throws GeneralSecurityException {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers, trustManagers, null);
        return context;
    }

    /** Trust-everything X509TrustManager used only by the explicit unsafe trust-all path. */
    static final class TrustAllManager implements X509TrustManager {

        static final TrustAllManager INSTANCE = new TrustAllManager();

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    private void connectAndLogin(FTPClient client) throws IOException, FtpException {
        client.connect(settings.host(), settings.port());
        int reply = client.getReplyCode();
        if (!FTPReply.isPositiveCompletion(reply)) {
            throw new FtpException(FtpExceptionType.AUTHENTICATION,
                    "Server refused connection for " + settings.host() + " reply=" + reply);
        }
        if (!client.login(settings.username(), settings.password())) {
            throw new FtpException(FtpExceptionType.AUTHENTICATION,
                    "Login failed for user '" + settings.username() + "'");
        }
        if (!client.setFileType(FTPClient.BINARY_FILE_TYPE)) {
            throw new FtpException(FtpExceptionType.PROTOCOL, "TYPE I failed during connect");
        }
        client.enterLocalPassiveMode();
        if (client instanceof FTPSClient ftps) {
            // Require data-channel protection for FTPS; failing here is a broken connection.
            ftps.execPBSZ(0);
            ftps.execPROT("P");
        }
    }

    private static void quietlyDisconnect(FTPClient client) {
        try {
            if (client.isConnected()) {
                try {
                    client.logout();
                } catch (IOException ignored) {
                    // best effort
                }
                client.disconnect();
            }
        } catch (IOException ignored) {
            // best effort
        }
    }
}