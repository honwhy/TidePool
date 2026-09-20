package io.ftppool.core;

/**
 * Generic lifetime contract the pool engines use to manufacture and discard
 * pooled resources (spec section 18 — ResourceFactory concept).
 *
 * <p>FTP handle creation/validation/reset/destroy is wired in the adapter via
 * {@link FtpConnectionFactory}.</p>
 *
 * @param <T> pooled resource type
 */
public interface ResourceFactory<T> {

    T create() throws Exception;

    boolean validate(T resource);

    /** Undo business state so the resource is safe to re-issue. */
    boolean reset(T resource);

    void destroy(T resource);
}