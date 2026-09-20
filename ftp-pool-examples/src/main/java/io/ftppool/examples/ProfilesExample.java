package io.ftppool.examples;

import io.ftppool.api.FtpPool;
import io.ftppool.core.FtpPoolBuilder;
import io.ftppool.core.FtpPoolProfiles;

/**
 * Shows the four convenience profiles (spec section 53/54). Each profile maps
 * onto the three separable axes — engine algorithm, lifecycle, observability:
 *
 * <pre>{@code
 * fast     → Fast engine, simple lifecycle, no observability
 * commons  → Commons engine, commons lifecycle, no observability
 * monitor  → Fast engine, simple lifecycle, full observability
 * hybrid   → Fast engine, commons lifecycle, full observability (default)
 * }</pre>
 *
 * <pre>{@code
 * java -cp ... io.ftppool.examples.ProfilesExample ftp.example.com
 * }</pre>
 */
public final class ProfilesExample {

    private ProfilesExample() {
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: ProfilesExample <host> [port] [username] [password]");
            return;
        }
        show("fast", FtpPoolProfiles.fast(), args);
        show("commons", FtpPoolProfiles.commons(), args);
        show("monitor", FtpPoolProfiles.monitor(), args);
        show("hybrid", FtpPoolProfiles.hybrid(), args);
    }

    @SuppressWarnings("TryFinallyCanBeTryWithResources")
    private static void show(String name, FtpPoolBuilder builder, String[] args) {
        String host = args[0];
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 21;
        String username = args.length > 2 ? args[2] : "anonymous";
        String password = args.length > 3 ? args[3] : "";

        FtpPool pool = builder
                .poolName(name)
                .host(host)
                .port(port)
                .username(username)
                .password(password)
                .minIdle(0)
                .maxSize(2)
                .build();
        try {
            System.out.println("profile '" + name + "' built, closed=" + pool.isClosed()
                    + ", total=" + pool.stats().total());
        } finally {
            pool.close();
        }
    }
}