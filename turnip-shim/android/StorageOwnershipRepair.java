package xyz.aethersx2.android.shim;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;

/** Import a deliberately staged ownership repair using the app's own identity. */
final class StorageOwnershipRepair {
    static final String STAGING = ".ownership-import-12812657";
    private static final String[] ENTRIES = {
        "PCSX2.ini", "turnip.conf", "gamesettings", "inputprofiles", "bios",
        "sstates", "memcards"
    };
    static boolean restore(File files) {
        if (files == null) return true; // no external-storage import to perform
        File source = new File(files, STAGING);
        if (!source.isDirectory() || new File(files, STAGING+".complete").isFile()) return true;
        try {
            for (String name : ENTRIES) {
                File entry = new File(source, name);
                if (entry.exists()) copyMissing(entry.toPath(), new File(files,name).toPath());
            }
            // Shader caches are disposable and may have inaccessible inherited
            // directories. Import only the original DLL; native preparation can
            // regenerate its own cache. Never let an optional cache block saves.
            Path dll = new File(source, "lsfg/Lossless.dll").toPath();
            if (Files.isRegularFile(dll)) {
                Path dest = new File(files, "lsfg/Lossless.dll").toPath();
                Files.createDirectories(dest.getParent());
                copyMissing(dll, dest);
            }
            // Marker is app-owned and OUTSIDE the shell-owned staging directory.
            Files.createFile(new File(files, STAGING+".complete").toPath());
            android.util.Log.i("VulkanShim", "Pink ownership import complete; originals retained");
            return true;
        } catch (Exception e) {
            android.util.Log.e("VulkanShim", "Pink ownership import stopped; originals retained: "+e);
            return false;
        }
    }
    static void copyMissing(Path source, Path dest) throws Exception {
        if (Files.isSymbolicLink(source)) throw new IOException("Import does not follow links");
        if (Files.isDirectory(source)) {
            Files.createDirectories(dest);
            try (java.nio.file.DirectoryStream<Path> children=Files.newDirectoryStream(source)) {
                for (Path child : children) copyMissing(child,dest.resolve(child.getFileName()));
            }
            return;
        }
        if (Files.exists(dest)) {
            if (!Arrays.equals(digest(source),digest(dest)))
                throw new IOException("Existing destination differs: "+dest.getFileName());
            normalAppPermissions(dest);
            return; // idempotent retry, never overwrite an existing state/card
        }
        Path temp=dest.getParent().resolve(".pink-import-"+java.util.UUID.randomUUID()+".tmp");
        Files.createFile(temp); // normal app file mode, not createTempFile's 0600
        try {
            try (java.io.InputStream in=Files.newInputStream(source);
                 java.io.OutputStream out=Files.newOutputStream(temp)) {
                byte[] block=new byte[65536]; int n;
                while ((n=in.read(block))!=-1) out.write(block,0,n);
            }
            if (!Arrays.equals(digest(source),digest(temp))) throw new IOException("Copy verification failed");
            normalAppPermissions(temp);
            Files.move(temp,dest); // no REPLACE_EXISTING; a concurrent destination wins
        } finally { Files.deleteIfExists(temp); }
    }
    private static void normalAppPermissions(Path path) throws IOException {
        // Match the app's ordinary external-data files (0660): app owner and
        // Android's data-management group. No permissions for other users/apps.
        // This also repairs 12812657's owner-only copies without rewriting bytes.
        Files.setPosixFilePermissions(path, java.util.EnumSet.of(
            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
            java.nio.file.attribute.PosixFilePermission.GROUP_READ,
            java.nio.file.attribute.PosixFilePermission.GROUP_WRITE));
    }

    private static byte[] digest(Path path) throws Exception {
        MessageDigest sha=MessageDigest.getInstance("SHA-256");
        try (java.io.InputStream in=Files.newInputStream(path)) {
            byte[] block=new byte[65536]; int n;
            while ((n=in.read(block))!=-1) sha.update(block,0,n);
        }
        return sha.digest();
    }
}
