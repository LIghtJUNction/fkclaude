package io.github.lightjunction.fkclaude;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/** Preserve prior bytes while ensuring the next JSONL event starts on its own line. */
final class AuditFileAppender {
    private AuditFileAppender() {}

    static long prepare(File file) throws IOException {
        try (RandomAccessFile existing = new RandomAccessFile(file, "rw")) {
            long size = existing.length();
            if (size == 0) return 0;
            existing.seek(size - 1);
            if (existing.read() == '\n') return size;
            // A crash may leave either valid JSON without LF or an incomplete JSON tail.
            // Keep both untouched; the reader can count an invalid tail independently.
            existing.seek(size);
            existing.write('\n');
            return size + 1;
        }
    }
}
