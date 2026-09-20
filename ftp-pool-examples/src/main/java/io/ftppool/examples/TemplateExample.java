package io.ftppool.examples;

import io.ftppool.api.FtpPool;
import io.ftppool.core.FtpClientTemplate;
import io.ftppool.core.FtpPoolBuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Demonstrates {@link FtpClientTemplate} (spec section 47): the same
 * borrow-execute-return semantics behind a single typed collaborator with
 * one-liners for upload / download / delete. This is also the surface the
 * Spring Boot starter exposes.
 *
 * <pre>{@code
 * java -cp ... io.ftppool.examples.TemplateExample ftp.example.com
 * }</pre>
 */
public final class TemplateExample {

    private TemplateExample() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: TemplateExample <host> [port] [username] [password]");
            return;
        }
        String host = args[0];
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 21;
        String username = args.length > 2 ? args[2] : "anonymous";
        String password = args.length > 3 ? args[3] : "";

        FtpPool pool = FtpPoolBuilder.builder()
                .poolName("template")
                .host(host)
                .port(port)
                .username(username)
                .password(password)
                .minIdle(0)
                .maxSize(3)
                .build();
        FtpClientTemplate ftp = new FtpClientTemplate(pool);
        try {
            String remotePath = "/tidepool-template.txt";
            ftp.upload(remotePath, new ByteArrayInputStream("template demo".getBytes(StandardCharsets.UTF_8)));

            String content = ftp.execute(connection -> {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                connection.download(remotePath, out);
                return out.toString(StandardCharsets.UTF_8);
            });
            System.out.println("download via template: " + content);

            ftp.delete(remotePath);
            System.out.println("health=" + ftp.health().getStatus());
        } finally {
            pool.close();
        }
    }
}