package io.ftppool.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PoolConfigurationTest {

    @Test
    void defaultsMatchSpec() {
        PoolConfiguration config = PoolConfiguration.createDefault();

        assertThat(config.getPoolName()).isEqualTo("default");
        assertThat(config.getMinIdle()).isEqualTo(2);
        assertThat(config.getMaxSize()).isEqualTo(20);
        assertThat(config.getConnectionTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(config.getSocketTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(config.getDataTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(config.getIdleTimeout()).isEqualTo(Duration.ofMinutes(5));
        assertThat(config.getMaxLifetime()).isEqualTo(Duration.ofMinutes(30));
        assertThat(config.getValidationInterval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(config.getMaxCreateConcurrency()).isEqualTo(2);
    }

    @Test
    void rejectsMinIdleAboveMaxSize() {
        assertThatThrownBy(() -> PoolConfiguration.builder().minIdle(5).maxSize(3).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minIdle must be <= maxSize");
    }

    @Test
    void invalidDurationsFallBackToDefaults() {
        PoolConfiguration config = PoolConfiguration.builder()
                .connectionTimeout(Duration.ZERO)
                .maxLifetime(Duration.ofSeconds(-1))
                .build();

        assertThat(config.getConnectionTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(config.getMaxLifetime()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void zeroValidationIntervalDisablesBorrowValidation() {
        PoolConfiguration config = PoolConfiguration.builder()
                .validationInterval(Duration.ZERO)
                .build();

        assertThat(config.getValidationInterval()).isEqualTo(Duration.ZERO);
    }

    @Test
    void customValuesAreHonoured() {
        PoolConfiguration config = PoolConfiguration.builder()
                .poolName("prod")
                .minIdle(1)
                .maxSize(8)
                .maxCreateConcurrency(1)
                .build();

        assertThat(config.getPoolName()).isEqualTo("prod");
        assertThat(config.getMaxCreateConcurrency()).isEqualTo(1);
    }
}