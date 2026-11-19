package io.github.lightjunction.fkclaude;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/** Checksummed private snapshot. A valid-looking truncated Properties file is not valid policy. */
final class ProfileSnapshot {
    static final int MAX_BYTES = 256 * 1024;
    private static final String MAGIC = "fkclaude.profile.snapshot.v1";
    private ProfileSnapshot() {}

    static byte[] encode(String text) {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        if (payload.length > MAX_BYTES - 128) throw corrupt();
        byte[] header = (MAGIC + " " + payload.length + " " + digest(payload) + "\n")
                .getBytes(StandardCharsets.US_ASCII);
        byte[] result = Arrays.copyOf(header, header.length + payload.length);
        System.arraycopy(payload, 0, result, header.length, payload.length);
        return result;
    }

    static String decode(byte[] snapshot) {
        if (snapshot.length > MAX_BYTES) throw corrupt();
        int newline = -1;
        for (int i = 0; i < Math.min(snapshot.length, 128); i++)
            if (snapshot[i] == '\n') { newline = i; break; }
        if (newline < 0) throw corrupt();
        String[] header = new String(snapshot, 0, newline, StandardCharsets.US_ASCII).split(" ", -1);
        if (header.length != 3 || !MAGIC.equals(header[0])) throw corrupt();
        int length;
        try { length = Integer.parseInt(header[1]); }
        catch (NumberFormatException invalid) { throw corrupt(); }
        if (length < 0 || length != snapshot.length - newline - 1) throw corrupt();
        byte[] payload = Arrays.copyOfRange(snapshot, newline + 1, snapshot.length);
        if (!digest(payload).equals(header[2])) throw corrupt();
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(payload)).toString();
        } catch (CharacterCodingException invalid) { throw corrupt(); }
    }

    private static String digest(byte[] payload) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(payload);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >>> 4) & 15, 16));
                hex.append(Character.forDigit(b & 15, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Profile snapshot digest unavailable");
        }
    }

    private static IllegalStateException corrupt() {
        return new IllegalStateException("Profile snapshot is corrupt");
    }
}
