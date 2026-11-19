package io.github.lightjunction.fkclaude;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;
import static org.junit.Assert.*;

public final class AuditFileAppenderTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private static final String NEXT = "{\"schema\":\"fkclaude.audit.v1\",\"seq\":3}\n";

    private static void append(File file) throws Exception {
        Files.write(file.toPath(), NEXT.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
    }

    @Test public void truncatedTailCannotSwallowTheNextEvent() throws Exception {
        File file = temporary.newFile();
        String complete = "{\"schema\":\"fkclaude.audit.v1\",\"seq\":1}";
        String truncated = "{\"schema\":\"fkclaude.audit.v1\",\"seq\":2";
        String original = complete + "\n" + truncated;
        Files.write(file.toPath(), original.getBytes(StandardCharsets.UTF_8));

        long appendOffset = AuditFileAppender.prepare(file);
        assertEquals(Files.size(file.toPath()), appendOffset);
        append(file);

        assertEquals(original + "\n" + NEXT, new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        assertEquals(List.of(complete, truncated, NEXT.stripTrailing()), Files.readAllLines(file.toPath()));
    }

    @Test public void validJsonWithoutNewlineIsPreservedAsAnIndependentEvent() throws Exception {
        File file = temporary.newFile();
        String original = "{\"schema\":\"fkclaude.audit.v1\",\"seq\":2}";
        Files.write(file.toPath(), original.getBytes(StandardCharsets.UTF_8));
        assertEquals(original.getBytes(StandardCharsets.UTF_8).length + 1, AuditFileAppender.prepare(file));
        append(file);
        assertEquals(List.of(original, NEXT.stripTrailing()), Files.readAllLines(file.toPath()));
    }

    @Test public void existingLineBoundaryDoesNotGainBlankLines() throws Exception {
        File file = temporary.newFile();
        String original = "{\"seq\":2}\r\n";
        Files.write(file.toPath(), original.getBytes(StandardCharsets.UTF_8));
        long before = Files.size(file.toPath());
        assertEquals(before, AuditFileAppender.prepare(file));
        assertEquals(before, AuditFileAppender.prepare(file));
        append(file);
        assertEquals(original + NEXT, new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

    @Test public void emptyAndNewFilesStartDirectlyWithTheirFirstEvent() throws Exception {
        for (File file : new File[]{temporary.newFile(), new File(temporary.getRoot(), "new.jsonl")}) {
            assertEquals(0, AuditFileAppender.prepare(file));
            append(file);
            assertEquals(NEXT, new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        }
    }

    @Test public void repeatedRecoveryIsIdempotentAndPreservesAllOriginalBytes() throws Exception {
        File file = temporary.newFile();
        byte[] original = "{\"text\":\"截断".getBytes(StandardCharsets.UTF_8);
        Files.write(file.toPath(), original);
        assertEquals(original.length + 1, AuditFileAppender.prepare(file));
        assertEquals(original.length + 1, AuditFileAppender.prepare(file));
        byte[] recovered = Files.readAllBytes(file.toPath());
        assertArrayEquals(original, java.util.Arrays.copyOf(recovered, original.length));
        assertEquals('\n', recovered[original.length]);
        append(file);
        assertEquals(2, Files.readAllLines(file.toPath()).size());
    }
}
