package org.jduff;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public class FileDeDuplicator {

    public static class FileKey {
        /* only files that match on all of these fields will be considered for de-duplication */
        final long size;
        final boolean isReadable;
        final boolean isWritable;
        final boolean isExecutable;
        final boolean isHidden;
        final int posixModeBits;

        byte[] digest = null; // excluded from hash function, computed if necessary in equals
                              // function
        final Path path; // excluded from hash/equals function except to compute the digest on
                         // demand

        FileKey(Path path, long size, boolean isReadable, boolean isWritable, boolean isExecutable, boolean isHidden,
            int posixModeBits) {
            this.path = path;
            this.size = size;
            this.isReadable = isReadable;
            this.isWritable = isWritable;
            this.isExecutable = isExecutable;
            this.isHidden = isHidden;
            this.posixModeBits = posixModeBits;
        }

        public static FileKey getFileKey(Path path) throws IOException {
            long size = Files.size(path);
            boolean isReadable = Files.isReadable(path);
            boolean isWritable = Files.isWritable(path);
            boolean isExecutable = Files.isExecutable(path);
            boolean isHidden = Files.isHidden(path);
            int modeBits = getPosixModeBits(path);
            FileKey f = new FileKey(path, size, isReadable, isWritable, isExecutable, isHidden, modeBits);
            System.err.format("%s: %s%n", path, f);
            return f;
        }

        public byte[] getDigest() throws IOException {
            if (digest == null) {
                digest = getSha1Checksum(path);
            }
            return digest;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + (isExecutable ? 1231 : 1237);
            result = prime * result + (isHidden ? 1231 : 1237);
            result = prime * result + (isReadable ? 1231 : 1237);
            result = prime * result + (isWritable ? 1231 : 1237);
            result = prime * result + posixModeBits;
            result = prime * result + (int) (size ^ (size >>> 32));
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            try {
                if (this == obj)
                    return true;
                if (obj == null)
                    return false;
                if (!(obj instanceof FileKey))
                    return false;
                FileKey other = (FileKey) obj;
                if (size != other.size)
                    return false;
                if (isExecutable != other.isExecutable)
                    return false;
                if (isHidden != other.isHidden)
                    return false;
                if (isReadable != other.isReadable)
                    return false;
                if (isWritable != other.isWritable)
                    return false;
                if (posixModeBits != other.posixModeBits)
                    return false;
                if (Files.isSameFile(path, other.path))
                    return true;
                if (!Arrays.equals(getDigest(), other.getDigest()))
                    return false;
            } catch (IOException e) {
                throw new IllegalArgumentException(e);
            }
            return true;
        }

        @Override
        public String toString() {
            return "FileKey [size=" + size + ", digest=" + digest + ", isReadable=" + isReadable + ", isWritable="
                + isWritable + ", isExecutable=" + isExecutable + ", isHidden=" + isHidden + ", posixModeBits="
                + (posixModeBits == 0 ? 0 : "0" + Integer.toOctalString(posixModeBits)) + "]";
        }

        private static byte[] getSha1Checksum(Path file) throws IOException {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-1");
                Files.copy(file, new DigestOutputStream(nullOutputStream, digest));
                return digest.digest();
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalArgumentException(e);
            }
        }

        private static final OutputStream nullOutputStream = new OutputStream() {
            @Override
            public void write(int b) throws IOException {}

            @Override
            public void write(byte[] b) throws IOException {}

            @Override
            public void write(byte[] b, int off, int len) throws IOException {}
        };

        private static int getPosixModeBits(Path path) throws IOException {
            try {
                return getModeBitsFromPosixFilePermissions(Files.getPosixFilePermissions(path));
            } catch (UnsupportedOperationException e) {
                return 0;
            }
        }

        private static int getModeBitsFromPosixFilePermissions(Set<PosixFilePermission> permissions) {
            int mode = 0;
            for (PosixFilePermission perm : permissions) {
                switch (perm) {
                case OWNER_READ:
                    mode |= 0400;
                    break;
                case OWNER_WRITE:
                    mode |= 0200;
                    break;
                case OWNER_EXECUTE:
                    mode |= 0100;
                    break;
                case GROUP_READ:
                    mode |= 0040;
                    break;
                case GROUP_WRITE:
                    mode |= 0020;
                    break;
                case GROUP_EXECUTE:
                    mode |= 0010;
                    break;
                case OTHERS_READ:
                    mode |= 0004;
                    break;
                case OTHERS_WRITE:
                    mode |= 0002;
                    break;
                case OTHERS_EXECUTE:
                    mode |= 0001;
                    break;
                }
            }

            return mode;
        }

    }

    private final Map<FileKey, Path> candidates = new HashMap<>();

    public static void main(String[] args) throws IOException {

        FileDeDuplicator fdd = new FileDeDuplicator();

        if (args.length == 0) {
            System.err.println("Usgae: pkgr [readonly dirs] dir");
            System.exit(-1);
        } else if (args.length == 1) {
            fdd.addDir(args[0]);
        } else if (args.length > 1) {
            fdd.addReadOnlyDirs(Arrays.copyOf(args, args.length - 1));
            fdd.addDir(args[args.length - 1]);
        }
    }

    private void addDir(String... dirs) throws IOException {
        for (String dirString : dirs) {
            Path dir = Paths.get(dirString);
            Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (Files.isSymbolicLink(file)) {
                        // skip symlinks
                        return FileVisitResult.CONTINUE;
                    }
                    FileKey key = FileKey.getFileKey(file);
                    Path target;
                    if ((target = candidates.get(key)) != null) {
                        System.err.format("Going to replace %s with hardlink to %s%n", file, target);
                        /*
                         * create link with tempfile, if createLink fails original file shouldn't be
                         * affected
                         */
                        Path tempFile = file.resolveSibling(file.getFileName() + "." + ThreadLocalRandom.current() .nextInt() + ".aside");
                        Files.move(file, tempFile);
                        try {
                            Files.createLink(file, target);
                            // link succeeded, delete original file
                            Files.delete(tempFile);
                        } catch (UnsupportedOperationException e) {
                            // link failed, restore original file
                            Files.move(tempFile, file);
                            throw e;
                        }                    
                    } else {
                        candidates.put(key, file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    private void addReadOnlyDirs(String... dirs) throws IOException {
        for (String dirString : dirs) {
            Path dir = Paths.get(dirString);
            Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (Files.isSymbolicLink(file)) {
                        // skip symlinks
                        return FileVisitResult.CONTINUE;
                    }
                    candidates.put(FileKey.getFileKey(file), file);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

}
