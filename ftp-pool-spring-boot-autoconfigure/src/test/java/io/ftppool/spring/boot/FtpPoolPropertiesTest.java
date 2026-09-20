package io.ftppool.spring.boot;

import io.ftppool.api.FtpProtocol;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code spring.tidepool.ftp.*} properties: defaults and Spring Boot relaxed
 * binding (spec section 46).
 */
class FtpPoolPropertiesTest {

    @Test
    void defaultsMatchSpec() {
        FtpPoolProperties properties = new FtpPoolProperties();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getUsername()).isEqualTo("anonymous");
        assertThat(properties.getProtocol()).isEqualTo(FtpProtocol.FTP);
        assertThat(properties.getEncoding()).isEqualTo("UTF-8");
        assertThat(properties.getPoolName()).isEqualTo("default");

        assertThat(properties.getPool().getMode()).isEqualTo("hybrid");
        assertThat(properties.getPool().getMinIdle()).isEqualTo(2);
        assertThat(properties.getPool().getMaxSize()).isEqualTo(20);
        assertThat(properties.getPool().getConnectionTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(properties.getPool().getSocketTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.getPool().getDataTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(properties.getPool().getIdleTimeout()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.getPool().getMaxLifetime()).isEqualTo(Duration.ofMinutes(30));
        assertThat(properties.getPool().getValidationInterval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.getPool().getLeakDetectionThreshold()).isEqualTo(Duration.ofSeconds(30));

        assertThat(properties.getObservability().isEnabled()).isTrue();
        assertThat(properties.getObservability().isMetrics()).isTrue();
        assertThat(properties.getObservability().isJmx()).isTrue();
        assertThat(properties.getObservability().getSlowOperationThreshold()).isEqualTo(Duration.ofSeconds(3));

        assertThat(properties.getSsl().isTrustAll()).isFalse();
        assertThat(properties.getSsl().isHostnameVerification()).isTrue();
    }

    @Test
    void relaxedBindingPopulatesAllGroups() {
        Map<String, Object> map = new HashMap<>();
        map.put("spring.tidepool.ftp.host", "ftp.example.com");
        map.put("spring.tidepool.ftp.port", "2121");
        map.put("spring.tidepool.ftp.username", "alice");
        map.put("spring.tidepool.ftp.password", "s3cret");
        map.put("spring.tidepool.ftp.protocol", "ftps_explicit");
        map.put("spring.tidepool.ftp.encoding", "ISO-8859-1");
        map.put("spring.tidepool.ftp.pool-name", "orders");
        map.put("spring.tidepool.ftp.pool.mode", "monitor");
        map.put("spring.tidepool.ftp.pool.min-idle", "3");
        map.put("spring.tidepool.ftp.pool.max-size", "50");
        map.put("spring.tidepool.ftp.pool.connection-timeout", "4s");
        map.put("spring.tidepool.ftp.pool.idle-timeout", "2m");
        map.put("spring.tidepool.ftp.observability.enabled", "false");
        map.put("spring.tidepool.ftp.observability.slow-operation-threshold", "7s");
        map.put("spring.tidepool.ftp.ssl.trust-all", "true");
        map.put("spring.tidepool.ftp.ssl.hostname-verification", "false");

        FtpPoolProperties properties = bind(map);

        assertThat(properties.getHost()).isEqualTo("ftp.example.com");
        assertThat(properties.getPort()).isEqualTo(2121);
        assertThat(properties.getUsername()).isEqualTo("alice");
        assertThat(properties.getPassword()).isEqualTo("s3cret");
        assertThat(properties.getProtocol()).isEqualTo(FtpProtocol.FTPS_EXPLICIT);
        assertThat(properties.getEncoding()).isEqualTo("ISO-8859-1");
        assertThat(properties.getPoolName()).isEqualTo("orders");

        assertThat(properties.getPool().getMode()).isEqualTo("monitor");
        assertThat(properties.getPool().getMinIdle()).isEqualTo(3);
        assertThat(properties.getPool().getMaxSize()).isEqualTo(50);
        assertThat(properties.getPool().getConnectionTimeout()).isEqualTo(Duration.ofSeconds(4));
        assertThat(properties.getPool().getIdleTimeout()).isEqualTo(Duration.ofMinutes(2));

        assertThat(properties.getObservability().isEnabled()).isFalse();
        assertThat(properties.getObservability().getSlowOperationThreshold()).isEqualTo(Duration.ofSeconds(7));

        assertThat(properties.getSsl().isTrustAll()).isTrue();
        assertThat(properties.getSsl().isHostnameVerification()).isFalse();
    }

    private static FtpPoolProperties bind(Map<String, Object> map) {
        Binder binder = new Binder(ConfigurationPropertySources.from(
                new MapPropertySource("test", map)));
        return binder.bind(FtpPoolProperties.PREFIX, Bindable.of(FtpPoolProperties.class)).get();
    }
}