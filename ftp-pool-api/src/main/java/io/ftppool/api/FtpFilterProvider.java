package io.ftppool.api;

import java.util.List;

/**
 * SPI supplying observability {@link FtpFilter}s for a pool (spec sections
 * 16 / 18 / 38).
 *
 * <p>Kept in the dependency-free module so {@code FtpPoolBuilder} can discover
 * filter stacks purely via {@code META-INF/services} without core depending on
 * any observability implementation. The observability module registers a
 * provider named {@code "full"} (metrics → logging → slow-operation).</p>
 *
 * <p>Providers must be stateless or cheap to construct at build time.</p>
 */
public interface FtpFilterProvider {

    /** SPI registration name, e.g. {@code "full"} (see {@link #create}). */
    String name();

    /**
     * Build the filter chain for a pool.
     *
     * @param config the resolved pool settings (slow-operation threshold, pool
     *               name, …); never null
     * @return the ordered filters; may be empty but never null
     */
    List<FtpFilter> create(FtpFilterConfig config);
}