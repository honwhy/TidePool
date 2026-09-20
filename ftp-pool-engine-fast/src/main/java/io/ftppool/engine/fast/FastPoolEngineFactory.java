package io.ftppool.engine.fast;

import io.ftppool.api.PoolEngine;
import io.ftppool.core.PoolConfiguration;
import io.ftppool.core.PoolEngineFactory;
import io.ftppool.core.PoolStatsRecorder;
import io.ftppool.core.ResourceFactory;

/**
 * {@link PoolEngineFactory} registering the Hikari-inspired fast engine under
 * the name {@code "fast"} (spec section 19). Discovered via {@code META-INF/services}.
 */
public final class FastPoolEngineFactory implements PoolEngineFactory {

    /** Engine SPI name. */
    public static final String ENGINE_NAME = "fast";

    @Override
    public String name() {
        return ENGINE_NAME;
    }

    @Override
    public <T> PoolEngine<T> create(PoolConfiguration configuration,
                                    ResourceFactory<T> resourceFactory,
                                    PoolStatsRecorder stats) {
        return new FastPoolEngine<>(resourceFactory, configuration, stats);
    }
}