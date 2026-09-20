package io.ftppool.core;

/**
 * Convenience presets for the three separable axes (spec section 53):
 * engine algorithm, lifecycle manager and observability depth.
 *
 * <pre>{@code
 * FtpPool pool = FtpPoolProfiles.hybrid()
 *     .host("ftp.example.com")
 *     .username("user")
 *     .password(pass)
 *     .build();
 * }</pre>
 *
 * <p>Profile mapping:
 * <ul>
 *   <li>{@link #fast()}    → Fast engine, simple lifecycle, no observability</li>
 *   <li>{@link #commons()} → Commons engine, commons lifecycle, no observability</li>
 *   <li>{@link #monitor()} → Fast/Commons engine, simple lifecycle, full observability</li>
 *   <li>{@link #hybrid()}  → Fast engine, commons lifecycle, full observability (default)</li>
 * </ul></p>
 */
public final class FtpPoolProfiles {

    private FtpPoolProfiles() {
    }

    /** Fast path only: lowest borrow latency, no observability overhead. */
    public static FtpPoolBuilder fast() {
        return FtpPoolBuilder.builder()
                .engine(PoolEngineType.FAST)
                .lifecycle(LifecycleType.SIMPLE)
                .observability(ObservabilityType.NONE);
    }

    /** Commons Pool engine: lifecycle-first, explicit behavior. */
    public static FtpPoolBuilder commons() {
        return FtpPoolBuilder.builder()
                .engine(PoolEngineType.COMMONS)
                .lifecycle(LifecycleType.COMMONS)
                .observability(ObservabilityType.NONE);
    }

    /** Druid-inspired monitoring profile over the fast engine. */
    public static FtpPoolBuilder monitor() {
        return FtpPoolBuilder.builder()
                .engine(PoolEngineType.FAST)
                .lifecycle(LifecycleType.SIMPLE)
                .observability(ObservabilityType.FULL);
    }

    /** Production-recommended default: fast engine + robust lifecycle + observability. */
    public static FtpPoolBuilder hybrid() {
        return FtpPoolBuilder.builder()
                .engine(PoolEngineType.FAST)
                .lifecycle(LifecycleType.COMMONS)
                .observability(ObservabilityType.FULL);
    }
}