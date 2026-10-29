package one.edee.mcp.jdwp.fastlogpoint;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.objectweb.asm.Opcodes.*;

/**
 * Decoding which probes a class calls from its code and constant pool, in the shape JDWP returns
 * them (pool entries without the count). The fixture walks every variable-length instruction form
 * and every pool entry size, so a mis-sized instruction would derail the scan.
 */
@DisplayName("ProbeCallScanner")
class ProbeCallScannerTest {

	@Test
	@DisplayName("finds every invokestatic to a probe class, through switches, wide forms and every pool entry kind")
	void findsProbeCalls() throws Exception {
		final byte[] classFile = hostClass();
		final ClassReader reader = new ClassReader(classFile);
		final byte[] pool = Arrays.copyOfRange(classFile, 10, reader.header);

		assertThat(ProbeCallScanner.probeCalls(pool, reader.readUnsignedShort(8),
			ClassFileMethods.codeByMethod(classFile).values()))
			.containsExactly("p.McpFastLogpoint_iface", "p.McpFastLogpoint_one", "q.McpFastLogpoint_two");
	}

	@Test
	@DisplayName("a method ending in a lookupswitch without pairs decodes (8 operand bytes, not 12)")
	void decodesTerminalEmptyLookupSwitch() throws Exception {
		final byte[] classFile = hostClass();
		final ClassReader reader = new ClassReader(classFile);
		final byte[] pool = Arrays.copyOfRange(classFile, 10, reader.header);
		// iload_0 (pc 0); lookupswitch (pc 1) padded to 4: default = -1 (back to pc 0), npairs = 0.
		final byte[] code = {0x1A, (byte) 0xAB, 0, 0, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0, 0, 0, 0};

		assertThat(ProbeCallScanner.probeCalls(pool, reader.readUnsignedShort(8), List.of(code))).isEmpty();
	}

	@Test
	@DisplayName("undecodable code or pool is an error, not an empty answer")
	void rejectsGarbage() throws Exception {
		final byte[] classFile = hostClass();
		final ClassReader reader = new ClassReader(classFile);
		final byte[] pool = Arrays.copyOfRange(classFile, 10, reader.header);
		final int count = reader.readUnsignedShort(8);

		assertThatThrownBy(() -> ProbeCallScanner.probeCalls(pool, count, List.of(new byte[]{(byte) 0xFE})))
			.isInstanceOf(FastLogpointException.class).hasMessageContaining("unknown opcode");
		assertThatThrownBy(() -> ProbeCallScanner.probeCalls(pool, count, List.of(new byte[]{(byte) INVOKESTATIC, 0, 1})))
			.isInstanceOf(FastLogpointException.class).hasMessageContaining("not a method reference");
		assertThatThrownBy(() -> ProbeCallScanner.probeCalls(Arrays.copyOf(pool, pool.length / 2), count, List.of()))
			.isInstanceOf(FastLogpointException.class);
	}

	/**
	 * A class whose one method holds: a long constant (two pool slots), wide iload / iinc, a
	 * tableswitch and a lookupswitch at unaligned offsets, an invokedynamic (MethodHandle,
	 * NameAndType and InvokeDynamic entries), a field access to a probe class, a call to a class that
	 * merely contains the prefix, and probe calls through a class, a second package and an interface.
	 */
	private static byte[] hostClass() {
		final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		writer.visit(V17, ACC_PUBLIC, "p/Host", null, "java/lang/Object", null);
		final MethodVisitor mv = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "run", "(I)J", null, null);
		mv.visitCode();
		mv.visitLdcInsn(123_456_789_012L);
		mv.visitInsn(POP2);
		mv.visitVarInsn(ILOAD, 300);
		mv.visitInsn(POP);
		mv.visitIincInsn(300, 1000);
		mv.visitFieldInsn(GETSTATIC, "p/McpFastLogpoint_field", "ENABLED", "Z");
		mv.visitInsn(POP);
		mv.visitInvokeDynamicInsn("run", "()Ljava/lang/Runnable;", new Handle(H_INVOKESTATIC, "p/Bootstrap", "make",
			"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
			false));
		mv.visitInsn(POP);
		final Label first = new Label();
		final Label second = new Label();
		final Label third = new Label();
		final Label fourth = new Label();
		final Label done = new Label();
		mv.visitVarInsn(ILOAD, 0);
		mv.visitTableSwitchInsn(0, 1, done, first, second);
		mv.visitLabel(first);
		mv.visitMethodInsn(INVOKESTATIC, "p/McpFastLogpoint_one", "hit", "()V", false);
		mv.visitJumpInsn(GOTO, done);
		mv.visitLabel(second);
		mv.visitVarInsn(ILOAD, 0);
		mv.visitLookupSwitchInsn(done, new int[]{5, 900}, new Label[]{third, fourth});
		mv.visitLabel(third);
		mv.visitVarInsn(ILOAD, 0);
		mv.visitMethodInsn(INVOKESTATIC, "q/McpFastLogpoint_two", "hit", "(I)V", false);
		mv.visitMethodInsn(INVOKESTATIC, "p/NotMcpFastLogpoint_x", "hit", "()V", false);
		mv.visitJumpInsn(GOTO, done);
		mv.visitLabel(fourth);
		mv.visitMethodInsn(INVOKESTATIC, "p/McpFastLogpoint_iface", "hit", "()V", true);
		mv.visitLabel(done);
		mv.visitLdcInsn(2.5d);
		mv.visitInsn(POP2);
		mv.visitInsn(LCONST_0);
		mv.visitInsn(LRETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}
}
