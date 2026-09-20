package io.ftppool.adapter;

import io.ftppool.core.FtpSslConfig;
import org.apache.commons.net.ftp.FTPSClient;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import static org.assertj.core.api.Assertions.assertThat;

class CommonsNetFtpsTlsTest {

    @Test
    void secureDefaultsUseJvmTrustStore() throws Exception {
        assertThat(CommonsNetFtpConnectionFactory.resolveContext(FtpSslConfig.secureDefaults()))
                .isSameAs(SSLContext.getDefault());
    }

    @Test
    void explicitSslContextTakesPrecedence() throws Exception {
        SSLContext custom = SSLContext.getInstance("TLS");
        custom.init(null, null, null);

        assertThat(CommonsNetFtpConnectionFactory.resolveContext(FtpSslConfig.secureDefaults().withSslContext(custom)))
                .isSameAs(custom);
    }

    @Test
    void trustManagersBuildAPolicyContext() throws Exception {
        X509TrustManager tm = new CommonsNetFtpConnectionFactory.TrustAllManager();
        FtpSslConfig config = new FtpSslConfig(null, new TrustManager[]{tm}, null, false, true);

        SSLContext context = CommonsNetFtpConnectionFactory.resolveContext(config);

        assertThat(context).isNotNull();
        assertThat(context).isNotSameAs(SSLContext.getDefault());
    }

    @Test
    void secureDefaultKeepsHostnameVerificationEnabled() {
        FTPSClient client = new FTPSClient(false,
                CommonsNetFtpConnectionFactory.resolveContext(FtpSslConfig.secureDefaults()));

        CommonsNetFtpConnectionFactory.configureTls(client, FtpSslConfig.secureDefaults());

        assertThat(client.isEndpointCheckingEnabled()).isTrue();
        assertThat(client.getTrustManager()).isNotSameAs(CommonsNetFtpConnectionFactory.TrustAllManager.INSTANCE);
    }

    @Test
    void insecureTrustAllDisablesCertificateAndHostnameChecks() {
        FtpSslConfig config = FtpSslConfig.insecureTrustAll();

        assertThat(config.trustAll()).isTrue();
        assertThat(config.hostnameVerification()).isFalse();

        FTPSClient client = new FTPSClient(false, CommonsNetFtpConnectionFactory.trustAllContext());
        CommonsNetFtpConnectionFactory.configureTls(client, config);

        assertThat(client.getTrustManager()).isSameAs(CommonsNetFtpConnectionFactory.TrustAllManager.INSTANCE);
        assertThat(client.isEndpointCheckingEnabled()).isFalse();
    }

    @Test
    void trustAllConstituentPoliciesReflectUnsafeIntent() {
        FtpSslConfig config = FtpSslConfig.insecureTrustAll();

        assertThat(config.toString()).contains("trustAll=true").contains("hostnameVerification=false");
    }
}