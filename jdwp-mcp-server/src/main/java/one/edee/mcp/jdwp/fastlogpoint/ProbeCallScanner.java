package one.edee.mcp.jdwp.fastlogpoint;

import one.edee.mcp.jdwp.evaluation.FastLogpointProbeSource;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

/**
 * Finds the fast-logpoint probes a class actually calls: every {@code invokestatic} in the live
 * bytecode, resolved through the live constant pool, whose owner's simple name starts with
 * {@link FastLogpointProbeSource#CLASS_PREFIX}.
 * <p>
 * This is observed wiring, not bookkeeping: an agent transforming the class during a redefinition
 * can keep a call the server removed or drop one it added, and only the installed code tells. JDWP
 * returns both views in one index space — {@code Method.bytecodes()} with constant-pool operands
 * (HotSpot's {@code copy_bytecodes} reverses its internal rewriting) and
 * {@code ReferenceType.constantPool()} as the matching pool.
 */
final class ProbeCallScanner {
    private static final int INVOKESTATIC = 0xB8;
    private static final int TABLESWITCH = 0xAA;
    private static final int LOOKUPSWITCH = 0xAB;
    private static final int WIDE = 0xC4;
    private static final int IINC = 0x84;
    /**
     * Instruction length per opcode for the fixed-length ones; 0 marks variable or invalid opcodes.
     */
    private static final byte[] LENGTH = new byte[256];

    static {
        for (int op = 0x00; op <= 0xC9; op++) {
            LENGTH[op] = 1;
        }
        LENGTH[0x10] = 2; // bipush
        LENGTH[0x11] = 3; // sipush
        LENGTH[0x12] = 2; // ldc
        LENGTH[0x13] = 3; // ldc_w
        LENGTH[0x14] = 3; // ldc2_w
        for (int op = 0x15; op <= 0x19; op++) {
            LENGTH[op] = 2; // xload
        }
        for (int op = 0x36; op <= 0x3A; op++) {
            LENGTH[op] = 2; // xstore
        }
        LENGTH[IINC] = 3;
        for (int op = 0x99; op <= 0xA8; op++) {
            LENGTH[op] = 3; // if*, goto, jsr
        }
        LENGTH[0xA9] = 2; // ret
        LENGTH[TABLESWITCH] = 0;
        LENGTH[LOOKUPSWITCH] = 0;
        for (int op = 0xB2; op <= 0xB8; op++) {
            LENGTH[op] = 3; // get/put field/static, invokevirtual/special/static
        }
        LENGTH[0xB9] = 5; // invokeinterface
        LENGTH[0xBA] = 5; // invokedynamic
        LENGTH[0xBB] = 3; // new
        LENGTH[0xBC] = 2; // newarray
        LENGTH[0xBD] = 3; // anewarray
        LENGTH[0xC0] = 3; // checkcast
        LENGTH[0xC1] = 3; // instanceof
        LENGTH[WIDE] = 0;
        LENGTH[0xC5] = 4; // multianewarray
        LENGTH[0xC6] = 3; // ifnull
        LENGTH[0xC7] = 3; // ifnonnull
        LENGTH[0xC8] = 5; // goto_w
        LENGTH[0xC9] = 5; // jsr_w
    }

    private ProbeCallScanner() {
    }

    /**
     * Binary names of the probe classes called from {@code codes}.
     *
     * @param constantPool the pool as JDWP returns it (entries from index 1, no count)
     * @param count        the pool's {@code constant_pool_count}
     * @param codes        the code arrays of the class's methods
     * @throws FastLogpointException when the pool or the code cannot be decoded
     */
    static Set<String> probeCalls(byte[] constantPool, int count, Collection<byte[]> codes)
        throws FastLogpointException {
        final Pool pool = Pool.parse(constantPool, count);
        final Set<String> probes = new TreeSet<>();
        for (byte[] code : codes) {
            int pc = 0;
            while (pc < code.length) {
                final int op = code[pc] & 0xFF;
                if (op == INVOKESTATIC && pc + 2 < code.length) {
                    final String owner = pool.methodOwner(u2(code, pc + 1));
                    final String simpleName = owner.substring(owner.lastIndexOf('/') + 1);
                    if (FastLogpointService.isProbeClassName(simpleName)) {
                        probes.add(owner.replace('/', '.'));
                    }
                }
                pc += length(code, pc, op);
            }
        }
        return probes;
    }

    private static int length(byte[] code, int pc, int op) throws FastLogpointException {
        final int fixed = LENGTH[op];
        if (fixed > 0) {
            return fixed;
        }
        if (op == WIDE) {
            return pc + 1 < code.length && (code[pc + 1] & 0xFF) == IINC ? 6 : 4;
        }
        if (op == TABLESWITCH || op == LOOKUPSWITCH) {
            // Operands start at the next multiple of four from the method start: default + low + high
            // for tableswitch, default + npairs for lookupswitch (which may have no pairs at all).
            final int operands = (pc + 4) & ~3;
            final int header = op == TABLESWITCH ? 12 : 8;
            if (operands + header > code.length) {
                throw new FastLogpointException("truncated switch at bytecode offset " + pc);
            }
            if (op == TABLESWITCH) {
                final long entries = (long) s4(code, operands + 8) - s4(code, operands + 4) + 1;
                return checked(operands - pc + 12 + entries * 4, pc);
            }
            return checked(operands - pc + 8 + (long) s4(code, operands + 4) * 8, pc);
        }
        throw new FastLogpointException("unknown opcode 0x" + Integer.toHexString(op) + " at bytecode offset " + pc);
    }

    private static int checked(long length, int pc) throws FastLogpointException {
        if (length <= 0 || length > Integer.MAX_VALUE) {
            throw new FastLogpointException("corrupt switch at bytecode offset " + pc);
        }
        return (int) length;
    }

    private static int u2(byte[] data, int at) {
        return ((data[at] & 0xFF) << 8) | (data[at + 1] & 0xFF);
    }

    private static int s4(byte[] data, int at) {
        return (data[at] << 24) | ((data[at + 1] & 0xFF) << 16) | ((data[at + 2] & 0xFF) << 8) | (data[at + 3] & 0xFF);
    }

    /**
     * The parts of a constant pool needed to name a method's owner.
     */
    private record Pool(int[] tags, int[] first, String[] utf8) {
        private static final int UTF8 = 1;
        private static final int CLASS = 7;
        private static final int METHODREF = 10;
        private static final int INTERFACE_METHODREF = 11;

        static Pool parse(byte[] bytes, int count) throws FastLogpointException {
            final int[] tags = new int[count];
            final int[] first = new int[count];
            final String[] utf8 = new String[count];
            int at = 0;
            try {
                for (int index = 1; index < count; index++) {
                    final int tag = bytes[at] & 0xFF;
                    tags[index] = tag;
                    switch (tag) {
                        case UTF8 -> {
                            final int length = u2(bytes, at + 1);
                            utf8[index] = new DataInputStream(new ByteArrayInputStream(bytes, at + 1, 2 + length)).readUTF();
                            at += 3 + length;
                        }
                        case 3, 4 -> at += 5; // Integer, Float
                        case 5, 6 -> { // Long, Double take two slots
                            at += 9;
                            index++;
                        }
                        case CLASS, 8, 16, 19, 20 -> { // Class, String, MethodType, Module, Package
                            first[index] = u2(bytes, at + 1);
                            at += 3;
                        }
                        case 9, METHODREF, INTERFACE_METHODREF, 12, 17, 18 -> {
                            first[index] = u2(bytes, at + 1);
                            at += 5;
                        }
                        case 15 -> at += 4; // MethodHandle
                        default -> throw new FastLogpointException("unknown constant-pool tag " + tag + " at index " + index);
                    }
                }
            } catch (IOException | IndexOutOfBoundsException e) {
                throw new FastLogpointException("truncated constant pool: " + e.getMessage());
            }
            return new Pool(tags, first, utf8);
        }

        String methodOwner(int methodIndex) throws FastLogpointException {
            if (methodIndex <= 0 || methodIndex >= tags.length
                || (tags[methodIndex] != METHODREF && tags[methodIndex] != INTERFACE_METHODREF)) {
                throw new FastLogpointException("invokestatic operand #" + methodIndex + " is not a method reference");
            }
            final int classIndex = first[methodIndex];
            if (classIndex <= 0 || classIndex >= tags.length || tags[classIndex] != CLASS) {
                throw new FastLogpointException("method reference #" + methodIndex + " has no class");
            }
            final int nameIndex = first[classIndex];
            final @Nullable String name = nameIndex > 0 && nameIndex < tags.length ? utf8[nameIndex] : null;
            if (name == null) {
                throw new FastLogpointException("class #" + classIndex + " has no name");
            }
            return name;
        }
    }
}
