package io.ftppool.examples;

import io.ftppool.api.FtpPool;
import io.ftppool.core.FtpPoolBuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Minimal end-to-end example for the {@code execute} API (spec section 6.2 /
 * 52). Builds a pool via {@link FtpPoolBuilder}, then borrow-execute-return
 * upload / download / delete in one lambda each — no manual release, broken
 * connections are handled by the pool itself.
 *
 * <pre>{@code
 * java -cp ... io.ftppool.examples.QuickStartExample ftp.example.com 21 user '******'
 * }</pre>
 */
public final class QuickStartExample {

    private QuickStartExample() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: QuickStartExample <host> [port] [username] [password]");
            System.out.println("Example: QuickStartExample ftp.example.com 21 user '******'");
            return;
        }
        String host = args[0];
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 21;
        String username = args.length > 2 ? args[2] : "anonymous";
        String password = args.length > 3 ? args[3] : "";

        try (FtpPool pool = FtpPoolBuilder.builder()
                .poolName("quickstart")
                .host(host)
                .port(port)
                .username(username)
                .password(password)
                .minIdle(1)
                .maxSize(5)
                .build()) {

            String remotePath = "/tidepool-quickstart.txt";
            byte[] payload = "Hello from TidePool FtpPool".getBytes(StandardCharsets.UTF_8);

            pool.execute(connection -> connection.upload(remotePath, new ByteArrayInputStream(payload)));

            String content = pool.execute(connection -> {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                connection.download(remotePath, out);
                return out.toString(StandardCharsets.UTF_8);
            });
            System.out.println("downloaded: " + content);

            pool.execute(connection -> connection.delete(remotePath));

            System.out.println("stats: total=" + pool.stats().total()
                    + ", idle=" + pool.stats().idle()
                    + ", borrowed=" + pool.stats().borrowed()
                    + ", returned=" + pool.stats().returned());
        }
    }
}