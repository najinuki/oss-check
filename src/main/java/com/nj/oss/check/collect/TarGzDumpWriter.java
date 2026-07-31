package com.nj.oss.check.collect;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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

    /**
     * Writes the dump to {@code target}, creating parent directories as needed.
     *
     * <p>An existing file is never replaced: a dump is evidence, and a second
     * run writing over the first destroys the state someone was collecting in
     * the first place (DESIGN.md 3.1). The file is created with
     * {@link StandardOpenOption#CREATE_NEW} so that the check and the creation
     * are one step — a caller that looked first would still leave a window for
     * a concurrent run to slip in.
     *
     * <p>A write that fails part way removes what it created, so a retry is not
     * blocked by the wreckage of the attempt before it.
     *
     * @throws java.nio.file.FileAlreadyExistsException if {@code target} exists
     */
    public void write(RawDump dump, Path target) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        // Opened before the guarded block on purpose: if this throws, the file
        // was already there and is emphatically not ours to delete.
        OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW);
        try (out) {
            write(dump, out);
        } catch (IOException | RuntimeException e) {
            // A half-written archive would block every retry, since the path is
            // now taken and this writer refuses to replace what it finds. Only
            // a file CREATE_NEW just made can be removed here, so this can
            // never delete someone else's dump. A JVM killed mid-write still
            // leaves one behind — that is the residue of keeping the name
            // reserved from the first moment instead of writing elsewhere and
            // renaming.
            Files.deleteIfExists(target);
            throw e;
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
