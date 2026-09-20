package io.ftppool.spring.boot;

import io.ftppool.api.FtpPool;
import io.ftppool.core.FtpClientTemplate;
import io.ftppool.jmx.FtpPoolJmx;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;

/**
 * {@link FtpPoolAutoConfiguration} in a real (annotation-driven) Spring context.
 *
 * <p>No {@code spring-boot-test} JUnit extension is needed — the same conditions
 * and post-processors run through a plain {@link AnnotationConfigApplicationContext},
 * so these tests exercise the true auto-configuration path.</p>
 */
class FtpPoolAutoConfigurationTest {

    private static final String HOST = "ftp.example.com";

    private final MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
    private AnnotationConfigApplicationContext context;

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void wiresPoolAndTemplateBeans() {
        context = context(map(HOST));

        FtpPool pool = context.getBean(FtpPool.class);
        assertThat(pool.isClosed()).isFalse();
        assertThat(context.getBean(FtpClientTemplate.class).pool()).isSameAs(pool);
        // property knobs really reached the pool configuration
        assertThat(pool.stats().total()).isZero();
    }

    @Test
    void disabledViaPropertyYieldsNoPool() {
        Map<String, Object> map = map(HOST);
        map.put("spring.tidepool.ftp.enabled", "false");
        context = context(map);

        assertThat(context.getBeansOfType(FtpPool.class)).isEmpty();
        assertThat(context.getBeansOfType(FtpClientTemplate.class)).isEmpty();
    }

    @Test
    void missingHostFailsFast() {
        Throwable thrown = catchThrowable(() -> context = context(new HashMap<>()));

        assertThat(rootMessage(thrown)).contains("spring.tidepool.ftp.host");
    }

    @Test
    void invalidPoolModeIsRejected() {
        Map<String, Object> map = map(HOST);
        map.put("spring.tidepool.ftp.pool.mode", "turbo");

        Throwable thrown = catchThrowable(() -> context = context(map));

        assertThat(rootMessage(thrown)).contains("spring.tidepool.ftp.pool.mode");
    }

    @Test
    void bindsPoolGaugesToMeterRegistry() {
        Map<String, Object> map = map(HOST);
        map.put("spring.tidepool.ftp.pool-name", "metrics-test");
        context = context(map, true);

        MeterRegistry registry = context.getBean(MeterRegistry.class);

        Gauge size = registry.find("ftp.pool.size").gauge();
        assertThat(registry.find("ftp.pool.active").gauge()).isNotNull();
        assertThat(size.getId().getTags())
                .extracting(Tag::getKey, Tag::getValue)
                .contains(tuple("host", HOST), tuple("protocol", "ftp"));
    }

    @Test
    void registersAndUnregistersJmxBean() throws Exception {
        Map<String, Object> map = map(HOST);
        map.put("spring.tidepool.ftp.pool-name", "jmx-test");
        context = context(map);

        ObjectName name = FtpPoolJmx.objectName("jmx-test");
        assertThat(mBeanServer.isRegistered(name)).isTrue();
        assertThat(mBeanServer.getAttribute(name, "Total")).isEqualTo(0);

        context.close();
        context = null;
        assertThat(mBeanServer.isRegistered(name)).isFalse();
    }

    // ------------------------- helpers -------------------------

    private static Map<String, Object> map(String host) {
        Map<String, Object> map = new HashMap<>();
        map.put("spring.tidepool.ftp.host", host);
        // no eager peers in unit tests: min-idle 0 prevents housekeeper connection attempts
        map.put("spring.tidepool.ftp.pool.min-idle", "0");
        return map;
    }

    private static AnnotationConfigApplicationContext context(Map<String, Object> map) {
        return context(map, false);
    }

    private static AnnotationConfigApplicationContext context(Map<String, Object> map, boolean withRegistry) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", map));
        ctx.register(FtpPoolAutoConfiguration.class);
        ctx.register(ConfigurationPropertiesAutoConfiguration.class);
        if (withRegistry) {
            ctx.registerBean(SimpleMeterRegistry.class);
        }
        ctx.refresh();
        return ctx;
    }

    private static String rootMessage(Throwable thrown) {
        Throwable cause = thrown;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? "" : cause.getMessage();
    }
}