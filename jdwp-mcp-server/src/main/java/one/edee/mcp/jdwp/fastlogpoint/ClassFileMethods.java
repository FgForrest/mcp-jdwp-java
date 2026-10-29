package one.edee.mcp.jdwp.fastlogpoint;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Extracts the raw {@code code} array of every method from a class file — the bytes JDI's
 * {@link com.sun.jdi.Method#bytecodes()} reports for the live method. Used by the fast-logpoint
 * bytecode guard to tell whether the class file obtained from the class loader is the one the VM
 * is actually running.
 * <p>
 * ASM does not expose a method's code bytes, so this walks the class-file structure directly
 * (JVMS §4): constant pool, then fields and methods, keeping each method's {@code Code} attribute.
 */
final class ClassFileMethods {

    private ClassFileMethods() {
    }

    /**
     * Returns {@code name + descriptor} → {@code code} for every method that has a {@code Code}
     * attribute (abstract and native methods have none), in class-file order.
     *
     * @throws IllegalArgumentException when {@code classFile} is not a well-formed class file
     */
    static Map<String, byte[]> codeByMethod(byte[] classFile) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(classFile))) {
            if (in.readInt() != 0xCAFEBABE) {
                throw new IllegalArgumentException("not a class file (bad magic number)");
            }
            in.readUnsignedShort(); // minor_version
            in.readUnsignedShort(); // major_version
            final String[] utf8 = readConstantPoolUtf8(in);
            in.readUnsignedShort(); // access_flags
            in.readUnsignedShort(); // this_class
            in.readUnsignedShort(); // super_class
            final int interfaces = in.readUnsignedShort();
            skipFully(in, 2L * interfaces);
            final int fields = in.readUnsignedShort();
            for (int i = 0; i < fields; i++) {
                skipFully(in, 6); // access_flags, name_index, descriptor_index
                skipAttributes(in);
            }
            final Map<String, byte[]> result = new LinkedHashMap<>();
            final int methods = in.readUnsignedShort();
            for (int i = 0; i < methods; i++) {
                in.readUnsignedShort(); // access_flags
                final String name = utf8(utf8, in.readUnsignedShort());
                final String descriptor = utf8(utf8, in.readUnsignedShort());
                final int attributes = in.readUnsignedShort();
                for (int a = 0; a < attributes; a++) {
                    final String attributeName = utf8(utf8, in.readUnsignedShort());
                    final long length = in.readInt() & 0xFFFFFFFFL;
                    if ("Code".equals(attributeName)) {
                        in.readUnsignedShort(); // max_stack
                        in.readUnsignedShort(); // max_locals
                        final byte[] code = new byte[in.readInt()];
                        in.readFully(code);
                        result.put(name + descriptor, code);
                        skipFully(in, length - 8 - code.length);
                    } else {
                        skipFully(in, length);
                    }
                }
            }
            return result;
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("malformed class file: " + e.getMessage(), e);
        }
    }

    /**
     * Reads the constant pool and returns the {@code CONSTANT_Utf8} entries by index; every other
     * slot stays {@code null}.
     */
    private static String[] readConstantPoolUtf8(DataInputStream in) throws IOException {
        final int count = in.readUnsignedShort();
        final String[] utf8 = new String[count];
        for (int i = 1; i < count; i++) {
            final int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> utf8[i] = in.readUTF(); // Utf8: u2 length + modified UTF-8, as readUTF expects
                case 7, 8, 16, 19, 20 -> skipFully(in, 2); // Class, String, MethodType, Module, Package
                case 15 -> skipFully(in, 3); // MethodHandle
                case 3, 4, 9, 10, 11, 12, 17, 18 -> skipFully(in, 4); // Integer .. InvokeDynamic
                case 5, 6 -> { // Long, Double occupy two slots
                    skipFully(in, 8);
                    i++;
                }
                default -> throw new IOException("unknown constant pool tag " + tag + " at index " + i);
            }
        }
        return utf8;
    }

    private static String utf8(String[] utf8, int index) throws IOException {
        final String value = index > 0 && index < utf8.length ? utf8[index] : null;
        if (value == null) {
            throw new IOException("constant pool index " + index + " is not a Utf8 entry");
        }
        return value;
    }

    private static void skipAttributes(DataInputStream in) throws IOException {
        final int attributes = in.readUnsignedShort();
        for (int a = 0; a < attributes; a++) {
            in.readUnsignedShort(); // attribute_name_index
            skipFully(in, in.readInt() & 0xFFFFFFFFL);
        }
    }

    private static void skipFully(DataInputStream in, long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            final long skipped = in.skip(remaining);
            if (skipped <= 0) {
                throw new IOException("unexpected end of class file");
            }
            remaining -= skipped;
        }
    }
}
