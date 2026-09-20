package io.ftppool.api;

/**
 * Operation kinds tracked by filters, metrics and logging (section 36 of the spec).
 */
public enum FtpOperation {

    CONNECT,
    LOGIN,
    BORROW,
    RETURN,
    VALIDATE,
    RESET,
    LIST,
    UPLOAD,
    DOWNLOAD,
    DELETE,
    RENAME,
    MAKE_DIRECTORY,
    CHANGE_DIRECTORY,
    CURRENT_DIRECTORY,
    COMPLETE_PENDING,
    EXECUTE
}