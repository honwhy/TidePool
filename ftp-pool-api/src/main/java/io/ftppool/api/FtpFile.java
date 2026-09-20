package io.ftppool.api;

import java.util.Objects;

/**
 * Directory listing entry returned by {@link FtpConnection#listFiles}.
 *
 * <p>Deliberately NOT {@code org.apache.commons.net.ftp.FTPFile}: the api module
 * is dependency-free, the adapter maps Commons Net types into this record.</p>
 */
public record FtpFile(
        String name,
        long size,
        long lastModifiedMillis,
        boolean directory,
        boolean file,
        boolean symbolicLink,
        int hardLinkCount,
        String rawListing) {

    public FtpFile {
        Objects.requireNonNull(name, "name");
    }
}