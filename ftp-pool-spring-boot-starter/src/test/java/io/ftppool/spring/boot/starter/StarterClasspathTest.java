package io.ftppool.spring.boot.starter;

import io.ftppool.api.FtpPool;
import io.ftppool.core.FtpClientTemplate;
import io.ftppool.core.FtpConnectionFactoryProvider;
import io.ftppool.core.PoolEngineFactory;
import io.ftppool.spring.boot.FtpPoolAutoConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the starter aggregates everything promised by the spec (spec
 * sections 45 / 50): a single dependency brings API, core, adapter
 * (Commons Net), fast engine, observability, Micrometer and JMX on to the
 * classpath, and the {@code META-INF/services} SPIs resolve against them.
 */
class StarterClasspathTest {

    @Test
    void keyTypesAreOnTheClasspath() {
        assertThat(loadable("io.ftppool.api.FtpPool")).isTrue();
        assertThat(loadable("io.ftppool.api.FtpConnection")).isTrue();
        assertThat(loadable("io.ftppool.core.FtpClientTemplate")).isTrue();
        assertThat(loadable("io.ftppool.spring.boot.FtpPoolAutoConfiguration")).isTrue();
        assertThat(loadable("io.ftppool.micrometer.FtpMicrometer")).isTrue();
        assertThat(loadable("io.ftppool.jmx.FtpPoolJmx")).isTrue();
        assertThat(loadable("io.ftppool.observability.LoggingFilter")).isTrue();
        assertThat(loadable("io.ftppool.adapter.CommonsNetFtpConnectionFactory")).isTrue();
        assertThat(loadable("io.ftppool.engine.fast.FastPoolEngineFactory")).isTrue();
    }

    @Test
    void serviceProvidersResolve() {
        FtpConnectionFactoryProvider adapter = ServiceLoader.load(FtpConnectionFactoryProvider.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .filter(p -> p.supports(io.ftppool.api.FtpProtocol.FTP))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No FTP-capable FtpConnectionFactoryProvider on starter classpath"));
        assertThat(adapter.name()).isEqualTo("commons-net");

        PoolEngineFactory fastEngine = ServiceLoader.load(PoolEngineFactory.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .filter(p -> "fast".equals(p.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No 'fast' PoolEngineFactory on starter classpath"));
        assertThat(fastEngine).isNotNull();
    }

    @Test
    void apiRemainsLeakFreeOfCommonsNet() {
        // Users face FtpPool/FtpConnection; Commons Net must never leak through the API.
        assertNoCommonsNetLeak(FtpPool.class);
        assertNoCommonsNetLeak(FtpClientTemplate.class);
    }

    private static void assertNoCommonsNetLeak(Class<?> type) {
        for (java.lang.reflect.Method method : type.getMethods()) {
            assertThat(method.getReturnType().getName()).doesNotContain("org.apache.commons.net");
            for (Class<?> parameter : method.getParameterTypes()) {
                assertThat(parameter.getName()).doesNotContain("org.apache.commons.net");
            }
        }
    }

    private static boolean loadable(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}