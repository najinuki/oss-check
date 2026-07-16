package com.nj.oss.check.collect;

import java.io.IOException;

/**
 * Produces a {@link RawDump}. Implementations: live cluster collection over
 * HTTP ({@code diagnose --endpoint} / {@code collect}) and tar.gz archive
 * reading ({@code diagnose --input dump.tar.gz}).
 */
public interface DumpSource {

    RawDump load() throws IOException;
}