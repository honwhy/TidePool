package io.ftppool.engine.commons;

import io.ftppool.api.PoolEngine;
import io.ftppool.core.PoolConfiguration;
import io.ftppool.core.PoolEngineFactory;
import io.ftppool.core.PoolStatsRecorder;
import io.ftppool.core.ResourceFactory;

/**
 * {@link PoolEngineFactory} registering the Commons-Pool-based engine under the
 * name {@code "commons"} (spec section 19). Discovered via {@code META-INF/services}.
 */
public final class CommonsPoolEngineFactory implements PoolEngineFactory {

    /** Engine SPI name. */
    public static final String ENGINE_NAME = "commons";

    @Override
    public String name() {
        return ENGINE_NAME;
    }

    @Override
    public <T> PoolEngine<T> create(PoolConfiguration configuration,
                                    ResourceFactory<T> resourceFactory,
                                    PoolStatsRecorder stats) {
        return new CommonsPoolEngine<>(resourceFactory, configuration, stats);
    }
}