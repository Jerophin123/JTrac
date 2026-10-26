package com.jtrac.launcher;

import java.util.Locale;

/** The operating system and CPU architecture JTRAC runs on. */
record Platform(Os os, Arch arch) {

    enum Os { WINDOWS, LINUX, MACOS }

    enum Arch { X64, ARM64 }

    static Platform current() {
        String name = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        Os os = name.contains("win") ? Os.WINDOWS : name.contains("mac") || name.contains("darwin") ? Os.MACOS : Os.LINUX;
        String a = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        Arch arch = a.equals("aarch64") || a.equals("arm64") ? Arch.ARM64 : Arch.X64;
        return new Platform(os, arch);
    }

    /** Release target id, e.g. windows-x64, linux-arm64, macos-arm64. */
    String id() {
        return os.name().toLowerCase(Locale.ROOT) + "-" + arch.name().toLowerCase(Locale.ROOT);
    }

    String executable(String name) {
        return os == Os.WINDOWS ? name + ".exe" : name;
    }

    boolean supported() {
        String a = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        return a.equals("amd64") || a.equals("x86_64") || a.equals("aarch64") || a.equals("arm64");
    }
}
