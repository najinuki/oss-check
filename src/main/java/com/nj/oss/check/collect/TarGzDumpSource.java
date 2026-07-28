package com.nj.oss.check.collect;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.zip.GZIPInputStream;

/**
 * Reads a tar.gz dump back into a {@link RawDump} — the offline half of
 * {@code diagnose --input dump.tar.gz}. Touches no network.
 *
 * <p>Entries are matched to targets by <b>file name</b>, using the same
 * {@link CollectTarget} table {@link TarGzDumpWriter} names them with. Two
 * consequences worth stating plainly:
 *
 * <ul>
 *   <li>An entry whose name matches no target is <b>ignored</b>. That is what
 *       makes a dump from a newer version of the tool readable here, and it is
 *       also why renaming a file inside a dump makes its data disappear rather
 *       than be misread.</li>
 *   <li>Only the file name matters, not the path. A dump whose entries sit
 *       under a directory ({@code dump/cluster_health.json}) reads the same as
 *       a flat one, so an operator can hand-assemble a dump by tarring a
 *       directory of responses.</li>
 * </ul>
 */
public final class TarGzDumpSource implements DumpSource {

    private final Path archive;

    public TarGzDumpSource(Path archive) {
        this.archive = Objects.requireNonNull(archive, "archive");
    }

    @Override
    public RawDump load() throws IOException {
        try (InputStream in = Files.newInputStream(archive)) {
            return read(in);
        }
    }



    RawDump read(InputStream in) throws IOException {
        Map<String, CollectTarget> targetsByFileName = targetsByFileName();
        Map<CollectTarget, String> payloads = new EnumMap<>(CollectTarget.class);
        String metadataJson = null;

        try (TarArchiveInputStream tar = new TarArchiveInputStream(new GZIPInputStream(in))) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String fileName = fileNameOf(entry.getName());
                if (CollectTarget.METADATA_FILE_NAME.equals(fileName)) {
                    metadataJson = requireFirst(metadataJson, fileName, readEntry(tar));
                    continue;
                }
                CollectTarget target = targetsByFileName.get(fileName);
                if (target == null) {
                    continue;   // unknown file: a newer tool's target, or operator clutter
                }
                payloads.put(target, requireFirst(payloads.get(target), fileName, readEntry(tar)));
            }
        }

        if (metadataJson == null) {
            throw new IOException("Dump archive has no " + CollectTarget.METADATA_FILE_NAME + ": " + archive);
        }
        return new RawDump(metadataJson, payloads);
    }

    /**
     * A dump carrying the same file name twice is ambiguous — there is no
     * basis for picking one — so it fails rather than silently choosing.
     */
    private String requireFirst(String existing, String fileName, String read) throws IOException {
        if (existing != null) {
            throw new IOException("Dump archive contains " + fileName + " more than once: " + archive);
        }
        return read;
    }

    private static String readEntry(TarArchiveInputStream tar) throws IOException {
        return new String(tar.readAllBytes(), StandardCharsets.UTF_8);
    }

    /** Last path segment, tolerating both {@code /} and {@code \} separators. */
    private static String fileNameOf(String entryName) {
        int slash = Math.max(entryName.lastIndexOf('/'), entryName.lastIndexOf('\\'));
        return slash < 0 ? entryName : entryName.substring(slash + 1);
    }

    private static Map<String, CollectTarget> targetsByFileName() {
        Map<String, CollectTarget> byFileName = new HashMap<>();
        for (CollectTarget target : CollectTarget.values()) {
            byFileName.put(target.fileName(), target);
        }
        return byFileName;
    }
}
