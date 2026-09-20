package io.ftppool.core;

import io.ftppool.api.FtpConnection;

/**
 * FTP-specific resource factory: creates/validates/resets/destroys
 * {@link FtpConnection} handles (spec section 20).
 *
 * <p>Interface lives in core so pool engines and FtpPoolImpl can depend on it;
 * the Commons Net implementation lives in <code>ftp-pool-adapter</code>.</p>
 */
public interface FtpConnectionFactory extends ResourceFactory<FtpConnection> {
}