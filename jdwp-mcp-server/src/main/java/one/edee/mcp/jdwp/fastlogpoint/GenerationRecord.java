package one.edee.mcp.jdwp.fastlogpoint;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What one redefinition by this server left installed in a class, stored in the target VM (the
 * probes' {@code MCP_IMAGE} field) so that a later session can recognise the class exactly as this
 * server left it.
 * <p>
 * The snapshot is the bytecode read back through JDWP <em>after</em> the redefinition — what
 * HotSpot actually installed, including any constant-pool renumbering — never a re-encoding, so a
 * later session (possibly a different server version) compares live code with it byte for byte.
 *
 * @param targetClass binary name of the instrumented class
 * @param original    the class file before this server first instrumented the class
 * @param probes      binary names of the probe classes called from the installed code, sorted
 * @param installed   live code per method ({@code name + descriptor}), sorted by key
 */
public record GenerationRecord(String targetClass, byte[] original, List<String> probes,
                               Map<String, byte[]> installed) {
    private static final int MAGIC = 0x4D435049; // "MCPI"
    private static final int VERSION = 1;

    public GenerationRecord {
        final List<String> sorted = new ArrayList<>(probes);
        Collections.sort(sorted);
        probes = List.copyOf(sorted);
        installed = new TreeMap<>(installed);
    }

    /**
     * Serializes the record; the inverse of {@link #decode}.
     */
    public byte[] encode() {
        try {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream(original.length * 2 + 256);
            final DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeUTF(targetClass);
            writeBytes(out, original);
            out.writeInt(probes.size());
            for (String probe : probes) {
                out.writeUTF(probe);
            }
            out.writeInt(installed.size());
            for (Map.Entry<String, byte[]> method : installed.entrySet()) {
                out.writeUTF(method.getKey());
                writeBytes(out, method.getValue());
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("cannot encode the generation record of " + targetClass, e);
        }
    }

    /**
     * Parses a record written by {@link #encode}.
     *
     * @throws FastLogpointException when the bytes are not a record this version understands
     */
    public static GenerationRecord decode(byte[] encoded) throws FastLogpointException {
        try {
            final DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            if (in.readInt() != MAGIC) {
                throw new FastLogpointException("not a fast-logpoint generation record");
            }
            final int version = in.readInt();
            if (version != VERSION) {
                throw new FastLogpointException("generation record version " + version + " is not supported");
            }
            final String targetClass = in.readUTF();
            final byte[] original = readBytes(in, encoded.length);
            final int probeCount = in.readInt();
            checkCount(probeCount, encoded.length);
            final List<String> probes = new ArrayList<>(probeCount);
            for (int i = 0; i < probeCount; i++) {
                probes.add(in.readUTF());
            }
            final int methodCount = in.readInt();
            checkCount(methodCount, encoded.length);
            final Map<String, byte[]> installed = new TreeMap<>();
            for (int i = 0; i < methodCount; i++) {
                installed.put(in.readUTF(), readBytes(in, encoded.length));
            }
            if (in.available() != 0) {
                throw new FastLogpointException("generation record has trailing bytes");
            }
            return new GenerationRecord(targetClass, original, probes, installed);
        } catch (IOException e) {
            throw new FastLogpointException("generation record is truncated or corrupt: " + e.getMessage());
        }
    }

    /**
     * Whether {@code live} (method key → code, abstract and native methods omitted) is exactly the
     * installed snapshot: the same methods with identical code.
     */
    public boolean matches(Map<String, byte[]> live) {
        if (installed.size() != live.size()) {
            return false;
        }
        for (Map.Entry<String, byte[]> method : installed.entrySet()) {
            if (!Arrays.equals(method.getValue(), live.get(method.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private static void writeBytes(DataOutputStream out, byte[] data) throws IOException {
        out.writeInt(data.length);
        out.write(data);
    }

    private static byte[] readBytes(DataInputStream in, int limit) throws IOException, FastLogpointException {
        final int length = in.readInt();
        checkCount(length, limit);
        final byte[] data = new byte[length];
        in.readFully(data);
        return data;
    }

    private static void checkCount(int count, int limit) throws FastLogpointException {
        if (count < 0 || count > limit) {
            throw new FastLogpointException("generation record is corrupt (length " + count + ")");
        }
    }
}
