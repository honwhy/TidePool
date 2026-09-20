package io.ftppool.tests;

import org.apache.ftpserver.filesystem.nativefs.impl.NativeFtpFile;
import org.apache.ftpserver.ftplet.FtpFile;
import org.apache.ftpserver.ftplet.FileSystemView;
import org.apache.ftpserver.ftplet.User;

import java.io.File;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * MINA's default {@code NativeFileSystemView} maps an absolute virtual path
 * such as {@code /foo.txt} straight onto the host file-system root
 * ({@code C:\foo.txt} on Windows), which is not writable. That makes every
 * test flit between "works on Linux CI" and "550 Permission denied" on a
 * developer machine.
 *
 * <p>This view roots the entire virtual tree at the user's home directory:
 * {@code /} is the home dir, {@code /a/b.txt} is {@code <home>/a/b.txt},
 * and {@code ..} escapes are rejected. It reuses MINA's {@link NativeFtpFile}
 * (via reflection, since its only constructor is protected) so transfer,
 * listing and move semantics match the real server.</p>
 */
final class SandboxedFileSystemView implements FileSystemView {

    private final User user;
    private final Path root;
    private volatile String cwd = "/";

    SandboxedFileSystemView(User user) {
        this.user = user;
        this.root = Path.of(user.getHomeDirectory()).toAbsolutePath().normalize();
    }

    @Override
    public FtpFile getHomeDirectory() {
        return file("/", root);
    }

    @Override
    public FtpFile getWorkingDirectory() {
        return file(cwd, resolve(cwd));
    }

    @Override
    public FtpFile getFile(String name) {
        String virtual = name.startsWith("/") ? name : join(cwd, name);
        return file(virtual, resolve(virtual));
    }

    @Override
    public boolean changeWorkingDirectory(String dir) {
        Path target = resolve(dir.startsWith("/") ? dir : join(cwd, dir));
        if (Files.isDirectory(target)) {
            String virtual = root.relativize(target).toString().replace('\\', '/');
            cwd = virtual.isEmpty() ? "/" : "/" + virtual;
            return true;
        }
        return false;
    }

    @Override
    public boolean isRandomAccessible() {
        return false;
    }

    @Override
    public void dispose() {
    }

    private static String join(String base, String name) {
        String joined = (base.equals("/") ? "" : base) + "/" + name;
        return joined.replaceAll("/{2,}", "/");
    }

    private Path resolve(String virtual) {
        Path target = root;
        for (String part : virtual.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                throw new IllegalArgumentException("path escape not allowed: " + virtual);
            }
            target = target.resolve(part);
        }
        return target.toAbsolutePath().normalize();
    }

    private FtpFile file(String virtual, Path physical) {
        try {
            Constructor<NativeFtpFile> constructor = NativeFtpFile.class
                    .getDeclaredConstructor(String.class, File.class, User.class);
            constructor.setAccessible(true);
            return constructor.newInstance(virtual, physical.toFile(), user);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot build NativeFtpFile for " + virtual, e);
        }
    }
}