package io.ftppool.core;

/**
 * Lifecycle management strategy (spec section 18) — the axis that decides how
 * much background maintenance the pool performs on idle connections.
 *
 * <ul>
 *   <li>{@link #SIMPLE} — no background maintenance: no idle eviction, no
 *       max-lifetime retirement while idle, no periodic validation. Min-idle is
 *       still filled once at startup. Lowest overhead; connection hygiene is
 *       limited to reset-on-return.</li>
 *   <li>{@link #COMMONS} — Commons-Pool-style lifecycle: periodic housekeeper
 *       for idle eviction, max-lifetime retirement, validation-interval checks
 *       and continuous min-idle refill. Production default.</li>
 * </ul>
 *
 * <p>The Commons pool engine is inherently lifecycle-{@code COMMONS}; this axis
 * is what lets the Fast engine be combined with a robust lifecycle (the
 * {@code hybrid} profile).</p>
 */
public enum LifecycleType {
    SIMPLE,
    COMMONS
}