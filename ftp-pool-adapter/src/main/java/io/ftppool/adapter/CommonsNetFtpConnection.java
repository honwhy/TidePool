package io.ftppool.adapter;

import io.ftppool.api.FtpConnection;
import io.ftppool.api.FtpConnectionId;
import io.ftppool.api.FtpException;
import io.ftppool.api.FtpExceptionType;
import io.ftppool.api.FtpFile;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

/**
 * {@link FtpConnection} backed by an Apache Commons Net {@link FTPClient}.
 *
 * <p>All Commons Net types are mapped at this seam; nothing above this class
 * ever sees {@code FTPClient} (spec section 51).</p>
 *
 * <p>Transfer state is tracked locally ({@link #pendingTransfer}) so the pool can
 * guarantee a connection is never re-issued while a data connection is open.</p>
 */
@Slf4j
final class CommonsNetFtpConnection implements FtpConnection {

    private final FtpConnectionId id;
    private final FTPClient client;
    private final String initialDirectory;
    private volatile boolean broken;

    /** True while a RETR/STOR data channel is open and {@link #completePendingCommand()} is pending. */
    private volatile boolean pendingTransfer;

    CommonsNetFtpConnection(FtpConnectionId id, FTPClient client, String initialDirectory) {
        this.id = Objects.requireNonNull(id, "id");
        this.client = Objects.requireNonNull(client, "client");
        this.initialDirectory = initialDirectory;
    }

    @Override
    public FtpConnectionId id() {
        return id;
    }

    @Override
    public void changeDirectory(String path) throws FtpException {
        try {
            if (!client.changeWorkingDirectory(path)) {
                throw FtpExceptionTranslator.business("CWD failed for '" + mask(path) + "' reply=" + client.getReplyString());
            }
        } catch (IOException e) {
            throw FtpExceptionTranslator.connection("CWD failed for '" + mask(path) + "'", e);
        }
    }

    @Override
    public String currentDirectory() throws FtpException {
        try {
            return client.printWorkingDirectory();
        } catch (IOException e) {
            throw FtpExceptionTranslator.connection("PWD failed", e);
        }
    }

    @Override
    public InputStream retrieveFileStream(String path) throws FtpException {
        try {
            InputStream in = client.retrieveFileStream(path);
            if (in == null) {
                throw FtpExceptionTranslator.business("RETR failed for '" + mask(path) + "' reply=" + client.getReplyString());
            }
            pendingTransfer = true;
            return in;
        } catch (IOException e) {
            throw FtpExceptionTranslator.connection("RETR failed for '" + mask(path) + "'", e);
        }
    }

    @Override
    public OutputStream storeFileStream(String path) throws FtpException {
        try {
            OutputStream out = client.storeFileStream(path);
            if (out == null) {
                throw FtpExceptionTranslator.business("STOR failed for '" + mask(path) + "' reply=" + client.getReplyString());
            }
            pendingTransfer = true;
            return out;
        } catch (IOException e) {
            throw FtpExceptionTranslator.connection("STOR failed for '" + mask(path) + "'", e);
        }
    }

    @Override
    public boolean upload(String path, InputStream input) throws FtpException {
        Objects.requireNonNull(input, "input");
        // The data stream must be closed BEFORE completePendingCommand():
        // commons-net only reads the final reply there and never closes the
        // data socket itself, so the server waits for EOF before sending 226.
        try (OutputStream out = storeFileStream(path)) {
            input.transferTo(out);
        } catch (IOException e) {
            pendingTransfer = false;
            markBroken();
            throw FtpExceptionTranslator.connection("Upload failed for '" + mask(path) + "'", e);
        }
        completePendingCommand();
        return true;
    }

    @Override
    public boolean download(String path, OutputStream output) throws FtpException {
        Objects.requireNonNull(output, "output");
        try (InputStream in = retrieveFileStream(path)) {
            in.transferTo(output);
        } catch (IOException e) {
            pendingTransfer = false;
            markBroken();
            throw FtpExceptionTranslator.connection("Download failed for '" + mask(path) + "'", e);
        }
        completePendingCommand();
        return true;
    }

    @Override
    public boolean delete(String path) throws FtpException {
        try {
            if (client.deleteFile(path)) {
                return true;
            }
            throw FtpExceptionTranslator.business("DELE failed for '" + mask(path) + "' reply=" + client.getReplyString());
        } catch (IOException e) {
            throw FtpExceptionTranslator.connection("DELE failed for '" + mask(path) + "'", e);
        }
    }

    @Override
    public boolean rename(String from, String to) throws FtpException {
        try {
            if (client.rename(from, to)) {
                return true;
            }
            throw FtpExceptionTranslator.business(
                    "RNFR/RNTO failed for '" + mask(from) + "' -> '" + mask(to) + "' reply=" + client.getReplyString());
        } catch (IOException e) {
            throw FtpExceptionTranslator.connection(
                    "RNFR/RNTO failed for '" + mask(from) + "' -> '" + mask(to) + "'", e);
        }
    }

    @Override
    public boolean makeDirectory(String path) throws FtpException {
        try {
            if (client.makeDirectory(path)) {
                return true;
            }
            throw FtpExceptionTranslator.business("MKD failed for '" + mask(path) + "' reply=" + client.getReplyString());
        } catch (IOException e) {
            throw FtpExceptionTranslator.connection("MKD failed for '" + mask(path) + "'", e);
        }
    }

    @Override
    public FtpFile[] listFiles(String path) throws FtpException {
        try {
            FTPFile[] files = client.listFiles(path);
            FtpFile[] mapped = new FtpFile[files.length];
            for (int i = 0; i < files.length; i++) {
                mapped[i] = toFtpFile(files[i]);
            }
            return mapped;
        } catch (IOException e) {
            throw FtpExceptionTranslator.connection("LIST failed for '" + mask(path) + "'", e);
        }
    }

    @Override
    public void completePendingCommand() throws FtpException {
        try {
            boolean ok = client.completePendingCommand();
            pendingTransfer = false;
            if (!ok) {
                throw FtpExceptionTranslator.business("Transfer completed with non-success reply: " + client.getReplyString());
            }
        } catch (IOException e) {
            pendingTransfer = false;
            markBroken();
            throw FtpExceptionTranslator.connection("Transfer aborted", e);
        }
    }

    @Override
    public boolean isValid() {
        try {
            return client.sendNoOp();
        } catch (IOException e) {
            markBroken();
            log.debug("NOOP failed for {}: {}", id, e.toString());
            return false;
        }
    }

    @Override
    public void markBroken() {
        broken = true;
    }

    @Override
    public boolean isBroken() {
        return broken;
    }

    FTPClient client() {
        return client;
    }

    boolean hasPendingTransfer() {
        return pendingTransfer;
    }

    /**
     * Restore a reusable, unpolluted control connection (spec section 9/10).
     * A failure here means the pool destroys rather than re-issues the entry.
     */
    void resetState() throws FtpException {
        if (pendingTransfer) {
            completePendingCommand();
        }
        if (initialDirectory != null && !initialDirectory.isEmpty()) {
            changeDirectory(initialDirectory);
        }
        try {
            if (!client.setFileType(FTPClient.BINARY_FILE_TYPE)) {
                throw new FtpException(FtpExceptionType.PROTOCOL,
                        "TYPE I failed during state reset for " + id);
            }
            client.enterLocalPassiveMode();
        } catch (IOException e) {
            throw FtpExceptionTranslator.connection("State reset failed for " + id, e);
        }
    }

    @Override
    public String toString() {
        return "CommonsNetFtpConnection[id=" + id + ", pendingTransfer=" + pendingTransfer + "]";
    }

    private static FtpFile toFtpFile(FTPFile file) {
        return new FtpFile(
                file.getName(),
                file.getSize(),
                file.getTimestamp() == null ? 0L : file.getTimestamp().getTimeInMillis(),
                file.isDirectory(),
                file.isFile(),
                file.isSymbolicLink(),
                file.getHardLinkCount(),
                file.getRawListing());
    }

    /** Masks paths for logs; full path is fine, no credentials inside. */
    private static String mask(String path) {
        return path == null ? "" : path;
    }
}