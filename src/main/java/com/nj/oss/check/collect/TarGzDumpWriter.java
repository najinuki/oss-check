package com.nj.oss.check.collect;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

/**
 * Writes a {@link RawDump} to a tar.gz archive.
 *
 * <p>Entries are written flat at the archive root, named by
 * {@link CollectTarget#fileName()} — the same enum {@link TarGzDumpSource}
 * reads them back with, so a dump written here always reads back identically.
 *
 * <p>The archive is meant to be openable with a plain {@code tar -xzf}: an
 * operator who cannot run this tool on the analysis host must still be able to
 * look at the responses.
 */
public final class TarGzDumpWriter {

    /** Writes the dump to {@code target}, creating parent directories as needed. */
    public void write(RawDump dump, Path target) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream out = Files.newOutputStream(target)) {
            write(dump, out);
        }
    }

    public void write(RawDump dump, OutputStream out) throws IOException {
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GZIPOutputStream(out))) {
            writeEntry(tar, CollectTarget.METADATA_FILE_NAME, dump.metadataJson());
            // Iterate the enum, not the map, so entry order is the declared
            // target order rather than whatever the map yields.
            for (CollectTarget target : CollectTarget.values()) {
                String payload = dump.payloads().get(target);
                if (payload != null) {
                    writeEntry(tar, target.fileName(), payload);
                }
            }
            tar.finish();
        }
    }

    private void writeEntry(TarArchiveOutputStream tar, String name, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(bytes.length);
        tar.putArchiveEntry(entry);
        tar.write(bytes);
        tar.closeArchiveEntry();
    }
}
