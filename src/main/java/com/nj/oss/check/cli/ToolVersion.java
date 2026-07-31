package com.nj.oss.check.cli;

/**
 * The version this build stamps into dumps and prints for {@code --version}.
 *
 * <p>Read from the jar manifest rather than duplicated as a literal, so a dump
 * can never claim a version the build did not produce. Running from plain
 * classes (development, tests) has no manifest, and such a dump says so.
 */
public final class ToolVersion {

    public static final String VERSION = versionFromManifest();

    private ToolVersion() {
    }

    private static String versionFromManifest() {
        String version = ToolVersion.class.getPackage().getImplementationVersion();
        return version != null ? version : "dev";
    }
}
