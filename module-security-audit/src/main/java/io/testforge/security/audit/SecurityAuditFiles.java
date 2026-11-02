package io.testforge.security.audit;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.Set;

/** Private local I/O; existing ancestors must be canonical and not symlinks. */
final class SecurityAuditFiles {
    static void noLinks(Path path) throws IOException {
        if (!path.isAbsolute() || !path.normalize().equals(path)) throw new IOException();
        for (Path part = path; part != null; part = part.getParent())
            if (Files.isSymbolicLink(part)) throw new IOException();
    }

    static Path directory(String root, String prefix) throws IOException {
        Path path = Path.of(root).toAbsolutePath().normalize();
        noLinks(path);
        Files.createDirectories(path);
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException();
        try {
            return Files.createTempDirectory(
                    path,
                    prefix,
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rwx------")));
        } catch (UnsupportedOperationException e) {
            return Files.createTempDirectory(path, prefix);
        }
    }

    static byte[] read(Path path, int max) throws IOException {
        noLinks(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > max)
            throw new IOException();
        Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        try (var channel = Files.newByteChannel(path, options);
                var input = Channels.newInputStream(channel)) {
            byte[] bytes = input.readNBytes(max + 1);
            if (bytes.length > max) throw new IOException();
            return bytes;
        }
    }

    static boolean remove(Path directory) {
        try (var files = Files.walk(directory, 32)) {
            var entries = files.limit(10001).toList();
            if (entries.size() > 10000) return false;
            for (Path file : entries.stream().sorted(Comparator.reverseOrder()).toList())
                Files.deleteIfExists(file);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private SecurityAuditFiles() {}
}
