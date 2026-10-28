package one.edee.mcp.jdwp.fastlogpoint;

import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Bytecode surgery for fast logpoints, built on the ASM tree API (the server targets Java 17;
 * {@code java.lang.classfile} needs JDK 24).
 * <p>
 * Every instrumentation is computed from the class's <em>original</em> bytes plus the full set of
 * probe sites currently armed in that class, so arming and disarming one site never compounds on
 * a previously instrumented version. A site is addressed by method (name + descriptor) and source
 * line; the insertion point is the line's lowest-offset {@code LineNumberTable} entry in that
 * method — the same location JDI's {@code locationsOfLine(line).get(0)} binds a breakpoint to. The
 * tree API does not expose bytecode offsets of labels read from a class file, but its instruction
 * list is in bytecode order, so "lowest offset" is "first in the list" and "local variable in scope"
 * is a comparison of list positions. The call is inserted after the label, line-number entry and
 * stack-map frame at that offset, so jumps to the line land on the probe call, the frame still
 * describes the state there, and a breakpoint on the same line still fires before the probe runs.
 * <p>
 * The inserted sequence only loads locals and calls a static {@code void} method, so it is
 * stack-neutral: existing stack-map frames stay valid and only {@code max_stack} is recomputed
 * ({@link ClassWriter#COMPUTE_MAXS}), which never needs to load classes. Copying the constant pool
 * from the original reader keeps every existing constant-pool index unchanged.
 */
final class ProbeInstrumenter {

    private ProbeInstrumenter() {
    }

    /**
     * Locates the insertion point of {@code line} in method {@code methodName + methodDescriptor}
     * and the local variables visible there.
     *
     * @throws FastLogpointException when the method or the line is not in the class file
     */
    static InsertionPoint locate(byte[] classFile, String methodName, String methodDescriptor, int line)
        throws FastLogpointException {
        final ClassNode classNode = read(classFile);
        final MethodNode method = findMethod(classNode, methodName, methodDescriptor);
        final LineNumberNode lineNode = findLine(method, line);
        final InsnList instructions = method.instructions;
        final int position = instructions.indexOf(lineNode.start);
        final List<LocalSlot> visible = new ArrayList<>();
        if (method.localVariables != null) {
            for (LocalVariableNode variable : method.localVariables) {
                final int start = instructions.indexOf(variable.start);
                if (start <= position && position < instructions.indexOf(variable.end)) {
                    visible.add(new LocalSlot(variable.name, variable.desc, variable.index, start));
                }
            }
        }
        return new InsertionPoint(visible, (method.access & Opcodes.ACC_STATIC) != 0);
    }

    /**
     * Returns the descriptor of the static method {@code methodName} declared in {@code classFile}.
     *
     * @throws FastLogpointException when the class declares no such method
     */
    static String methodDescriptor(byte[] classFile, String methodName) throws FastLogpointException {
        for (MethodNode method : read(classFile).methods) {
            if (method.name.equals(methodName) && (method.access & Opcodes.ACC_STATIC) != 0) {
                return method.desc;
            }
        }
        throw new FastLogpointException("Probe class declares no static method '" + methodName + "'");
    }

    /**
     * Returns {@code originalClassFile} with a call to each site's probe inserted at the site's
     * line. Each call goes in front of the line's current first instruction, so sites are applied
     * in descending {@link ProbeSite#id()} order — two sites on the same line then call their
     * probes in creation (ascending ID) order.
     *
     * @throws FastLogpointException when a site's method or line does not exist in the class file
     */
    static byte[] instrument(byte[] originalClassFile, Collection<ProbeSite> sites) throws FastLogpointException {
        final ClassReader reader = newReader(originalClassFile);
        final ClassNode classNode = new ClassNode(Opcodes.ASM9);
        reader.accept(classNode, 0);
        final List<ProbeSite> ordered = new ArrayList<>(sites);
        ordered.sort(Comparator.comparingInt(ProbeSite::id).reversed());
        for (ProbeSite site : ordered) {
            final MethodNode method = findMethod(classNode, site.methodName(), site.methodDescriptor());
            final LineNumberNode lineNode = findLine(method, site.line());
            final InsnList call = new InsnList();
            for (ArgumentLoad load : site.loads()) {
                call.add(new VarInsnNode(load.opcode(), load.slot()));
            }
            call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, site.probeInternalName(),
                site.probeMethodName(), site.probeMethodDescriptor(), false));
            final AbstractInsnNode firstInstruction = firstInstructionAfter(lineNode);
            if (firstInstruction == null) {
                throw new FastLogpointException("Line " + site.line() + " of " + site.methodName()
                    + " has no instruction to instrument");
            }
            method.instructions.insertBefore(firstInstruction, call);
        }
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        classNode.accept(writer);
        return writer.toByteArray();
    }

    /**
     * Returns the {@code xLOAD} opcode that pushes a local of {@code descriptor}.
     */
    static int loadOpcode(String descriptor) {
        return Type.getType(descriptor).getOpcode(Opcodes.ILOAD);
    }

    private static ClassNode read(byte[] classFile) throws FastLogpointException {
        final ClassNode classNode = new ClassNode(Opcodes.ASM9);
        newReader(classFile).accept(classNode, 0);
        return classNode;
    }

    private static ClassReader newReader(byte[] classFile) throws FastLogpointException {
        try {
            return new ClassReader(classFile);
        } catch (IllegalArgumentException unsupported) {
            // ASM rejects class-file versions newer than it knows.
            throw new FastLogpointException("Cannot parse the class file (unsupported class-file version?): "
                + unsupported.getMessage(), unsupported);
        }
    }

    private static MethodNode findMethod(ClassNode classNode, String name, String descriptor)
        throws FastLogpointException {
        for (MethodNode method : classNode.methods) {
            if (method.name.equals(name) && method.desc.equals(descriptor)) {
                return method;
            }
        }
        throw new FastLogpointException("Method " + name + descriptor + " not found in " + classNode.name);
    }

    /**
     * Returns the lowest-offset line-number entry for {@code line} in {@code method} — the first in
     * the instruction list, which is in bytecode order.
     */
    private static LineNumberNode findLine(MethodNode method, int line) throws FastLogpointException {
        for (AbstractInsnNode node : method.instructions) {
            if (node instanceof LineNumberNode lineNode && lineNode.line == line) {
                return lineNode;
            }
        }
        throw new FastLogpointException("Line " + line + " has no code in method " + method.name + method.desc);
    }

    /**
     * Returns the first real instruction after {@code lineNode}, skipping the labels, line numbers
     * and stack-map frames that share its offset.
     */
    @Nullable
    private static AbstractInsnNode firstInstructionAfter(LineNumberNode lineNode) {
        AbstractInsnNode node = lineNode.getNext();
        while (node != null && node.getOpcode() < 0) {
            node = node.getNext();
        }
        return node;
    }

    /**
     * What is in scope where a probe call goes in the original class file.
     *
     * @param visibleLocals  local variables in scope at the line's first instruction
     * @param staticMethod   whether the method is static (no {@code this} in slot 0)
     */
    record InsertionPoint(List<LocalSlot> visibleLocals, boolean staticMethod) {

        /**
         * Returns the visible local named {@code name}; with several (sequential scopes reusing a
         * name), the one whose scope started last — the same choice JDI's
         * {@code visibleVariables()} makes.
         */
        @Nullable
        LocalSlot local(String name) {
            LocalSlot best = null;
            for (LocalSlot slot : visibleLocals) {
                if (slot.name().equals(name) && (best == null || slot.scopeStart() > best.scopeStart())) {
                    best = slot;
                }
            }
            return best;
        }
    }

    /**
     * One {@code LocalVariableTable} entry visible at an insertion point; {@code scopeStart} is the
     * instruction-list position where its scope begins.
     */
    record LocalSlot(String name, String descriptor, int slot, int scopeStart) {
    }

    /**
     * One argument pushed before the probe call: an {@code xLOAD} of a local slot.
     */
    record ArgumentLoad(int opcode, int slot) {
    }

    /**
     * A probe call to insert.
     *
     * @param id                    fast-logpoint ID; orders sites that share a line
     * @param methodName            instrumented method name
     * @param methodDescriptor      instrumented method descriptor
     * @param line                  source line whose first instruction receives the call
     * @param probeInternalName     internal name of the probe class ({@code a/b/Probe})
     * @param probeMethodName       static method to call
     * @param probeMethodDescriptor its descriptor
     * @param loads                 the arguments, in parameter order
     */
    record ProbeSite(int id, String methodName, String methodDescriptor, int line,
                     String probeInternalName, String probeMethodName, String probeMethodDescriptor,
                     List<ArgumentLoad> loads) {
    }
}
