package io.ftppool.core;

import io.ftppool.api.PoolEngine;

/**
 * Pool engine factory SPI (spec sections 13 / 19), discovered via
 * {@code META-INF/services}. Registered by each engine module.
 */
public interface PoolEngineFactory {

    /** Engine name: "fast" | "commons". */
    String name();

    /**
     * Build an engine for a pool.
     *
     * @param configuration  pool tuning knobs
     * @param resourceFactory generic resource lifetime contract
     * @param stats          sink the engine reports create/destroy/borrow/return events into
     */
    <T> PoolEngine<T> create(PoolConfiguration configuration,
                             ResourceFactory<T> resourceFactory,
                             PoolStatsRecorder stats);
}