package io.ftppool.core;

import io.ftppool.api.FtpConnection;
import io.ftppool.api.FtpConnectionId;

import java.util.Objects;

/**
 * Pooled wrapper binding an {@link FtpConnectionId} to an {@link FtpConnection}.
 *
 * <p>This is the unit the pool engines manage (spec section 80 — FtpPoolEntry);
 * {@link FtpPoolImpl} hands out {@link #connection()} and maps it back here.</p>
 */
public final class FtpPoolEntry {

    private static final int LEAK_STACK_DEPTH = 12;

    private final FtpConnectionId id;
    private final FtpConnection connection;

    /** Leak-detection breadcrumbs (spec section 40); zero when not borrowed. */
    private volatile long borrowStartedAtMillis;
    private volatile String borrowThread;
    private volatile String borrowStackTrace;
    private volatile boolean leakReported;

    public FtpPoolEntry(FtpConnectionId id, FtpConnection connection) {
        this.id = Objects.requireNonNull(id, "id");
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    public FtpConnectionId id() {
        return id;
    }

    public FtpConnection connection() {
        return connection;
    }

    /** Records who borrowed this entry and when (called by FtpPoolImpl on borrow). */
    public void beginBorrow() {
        borrowStartedAtMillis = System.currentTimeMillis();
        borrowThread = Thread.currentThread().getName();
        borrowStackTrace = captureBorrowStack();
        leakReported = false;
    }

    /** Clears the borrow breadcrumbs (called by FtpPoolImpl on release). */
    public void endBorrow() {
        borrowStartedAtMillis = 0;
        borrowThread = null;
        borrowStackTrace = null;
    }

    public long borrowStartedAtMillis() {
        return borrowStartedAtMillis;
    }

    public String borrowThread() {
        return borrowThread;
    }

    public String borrowStackTrace() {
        return borrowStackTrace;
    }

    public boolean leakReported() {
        return leakReported;
    }

    public void markLeakReported() {
        leakReported = true;
    }

    @Override
    public String toString() {
        return "FtpPoolEntry[id=" + id + "]";
    }

    private static String captureBorrowStack() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        StringBuilder sb = new StringBuilder();
        int start = 4; // skip getStackTrace, captureBorrowStack, beginBorrow, FtpPoolImpl.borrow
        int end = Math.min(stack.length, start + LEAK_STACK_DEPTH);
        for (int i = start; i < end; i++) {
            sb.append("\n\tat ").append(stack[i]);
        }
        return sb.toString();
    }
}