package com.nj.oss.check.testsupport;

import com.nj.oss.check.collect.CollectTarget;
import com.nj.oss.check.collect.RawDump;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

/** Loads a fixture directory from test resources into a {@link RawDump}. */
public final class Fixtures {

    private Fixtures() {
    }

    public static RawDump load(String name) {
        Map<CollectTarget, String> payloads = new EnumMap<>(CollectTarget.class);
        for (CollectTarget target : CollectTarget.values()) {
            String content = read(name, target.fileName());
            if (content != null) {
                payloads.put(target, content);
            }
        }
        String metadata = read(name, CollectTarget.METADATA_FILE_NAME);
        if (metadata == null) {
            throw new IllegalStateException("Fixture \"" + name + "\" has no " + CollectTarget.METADATA_FILE_NAME);
        }
        return new RawDump(metadata, payloads);
    }

    private static String read(String fixture, String fileName) {
        String resource = "/fixtures/" + fixture + "/" + fileName;
        try (InputStream in = Fixtures.class.getResourceAsStream(resource)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read fixture resource " + resource, e);
        }
    }
}