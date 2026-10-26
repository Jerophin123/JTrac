package com.jtrac.serverconfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;

/**
 * Restricts files that hold secrets to the account that runs JTRAC: mode 600 (700 for
 * directories) on Linux and macOS, an owner-only access control list on Windows.
 */
public final class FilePermissions {

    private FilePermissions() {
    }

    public static void restrictToOwner(Path path) throws IOException {
        boolean directory = Files.isDirectory(path);
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (posix != null) {
            posix.setPermissions(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (acl != null) {
            UserPrincipal owner = acl.getOwner();
            AclEntry.Builder entry = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class));
            if (directory) {
                entry.setFlags(java.nio.file.attribute.AclEntryFlag.FILE_INHERIT, java.nio.file.attribute.AclEntryFlag.DIRECTORY_INHERIT);
            }
            // Replacing the list removes inherited entries (Users, Authenticated Users, ...).
            acl.setAcl(List.of(entry.build()));
        }
    }

    /** True when no other account can read the file (best effort per platform). */
    public static boolean isOwnerOnly(Path path) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (posix != null) {
            String mode = PosixFilePermissions.toString(posix.readAttributes().permissions());
            return mode.substring(3).equals("------");
        }
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (acl != null) {
            UserPrincipal owner = acl.getOwner();
            return acl.getAcl().stream().allMatch(e -> e.type() != AclEntryType.ALLOW || e.principal().equals(owner));
        }
        return false;
    }
}
