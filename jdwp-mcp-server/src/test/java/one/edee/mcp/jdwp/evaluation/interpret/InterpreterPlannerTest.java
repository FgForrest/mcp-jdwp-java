package one.edee.mcp.jdwp.evaluation.interpret;

import com.sun.jdi.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class InterpreterPlannerTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        item + 1           | NAVIGATE
        item == 2          | NAVIGATE
        true ? item : 9    | NAVIGATE
        false && item > 0  | NAVIGATE
        $tag               | NAVIGATE
        this.value         | NAVIGATE
        missing            | ERROR
        item +             | ERROR
        { return item; }   | COMPILE
        item++             | COMPILE
        item = 7           | COMPILE
        item.toString()    | INVOKE
        new Object()       | INVOKE
        item instanceof String s | INVOKE
        """)
    void plansDisjointEnginePaths(String source, Planner.KindOfPlan expected) throws Exception {
        final VirtualMachine vm = mock(VirtualMachine.class);
        final ClassType owner = type(vm, "app.Example", null);
        final StackFrame frame = mock(StackFrame.class);
        final Location location = mock(Location.class);
        final ObjectReference self = mock(ObjectReference.class);
        final LocalVariable item = mock(LocalVariable.class);
        final IntegerType intType = mock(IntegerType.class);
        final IntegerValue number = mock(IntegerValue.class);
        final Field privateValue = mock(Field.class);
        when(intType.name()).thenReturn("int");
        when(number.type()).thenReturn(intType);
        when(privateValue.name()).thenReturn("value");
        when(privateValue.declaringType()).thenReturn(owner);
        when(privateValue.type()).thenReturn(intType);
        when(privateValue.isPrivate()).thenReturn(true);
        when(owner.fields()).thenReturn(List.of(privateValue));
        when(item.name()).thenReturn("item");
        when(item.type()).thenReturn(intType);
        when(frame.location()).thenReturn(location);
        when(location.declaringType()).thenReturn(owner);
        when(frame.thisObject()).thenReturn(self);
        when(frame.visibleVariables()).thenReturn(List.of(item));
        when(frame.getValues(List.of(item))).thenReturn(Map.of(item, number));
        final var plan = Planner.plan(vm, frame, Map.of("$tag", number), ExpressionParser.parse(source));
        assertThat(plan.kind()).as(source).isEqualTo(expected);
        if (plan instanceof Planner.Error error && source.equals("missing")) {
            assertThat(error.message()).contains("Visible names", "item", "$tag");
        }
        verifyNoInteractions(self);
    }

    @Test
    void resolvesConstantPoolTypesThroughTheFrameLoaderAndRefusesAmbiguity() throws Exception {
        final VirtualMachine vm = mock(VirtualMachine.class);
        final ClassLoaderReference frameLoader = mock(ClassLoaderReference.class);
        final ClassLoaderReference parent = mock(ClassLoaderReference.class);
        final ClassType owner = type(vm, "app.Example", frameLoader);
        final ClassType one = type(vm, "first.Badge", parent);
        final ClassType two = type(vm, "second.Badge", parent);
        when(vm.canGetConstantPool()).thenReturn(true);
        when(owner.constantPool()).thenReturn(pool("first/Badge", "second/Badge"));
        when(owner.constantPoolCount()).thenReturn(5);
        when(frameLoader.visibleClasses()).thenReturn(List.of(one));

        assertThat(new JdiTypes(vm, owner).resolve("Badge")).isSameAs(one);
        assertThat(new JdiTypes(vm, owner).resolve("second.Badge")).isNull();
        when(frameLoader.visibleClasses()).thenReturn(List.of(one, two));
        final var declaring = mock(Location.class);
        final var frame = mock(StackFrame.class);
        when(declaring.declaringType()).thenReturn(owner);
        when(frame.location()).thenReturn(declaring);
        assertThat(Planner.plan(vm, frame, Map.of(), ExpressionParser.parse("Badge.class")))
            .isEqualTo(new Planner.Compile("ambiguous type name: Badge"));
    }

    @Test
    void bindsFieldsByDeclaredTypeAndRefusesHidingOnErasedReceivers() {
        final VirtualMachine vm = mock(VirtualMachine.class);
        final ClassType parent = type(vm, "app.Parent", null);
        final ClassType child = type(vm, "app.Child", null);
        final Field inherited = field(parent, "number");
        final Field hidden = field(child, "number");
        when(child.superclass()).thenReturn(parent);
        when(parent.fields()).thenReturn(List.of(inherited));
        when(child.fields()).thenReturn(List.of(hidden));
        when(child.allFields()).thenReturn(List.of(inherited, hidden));
        assertThat(JdiTypes.field(parent, "number", false)).isSameAs(inherited);
        assertThat(JdiTypes.field(child, "number", false)).isSameAs(hidden);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> JdiTypes.field(child, "number", true))
            .isInstanceOfSatisfying(Planner.Refusal.class, failure -> assertThat(failure.plan)
                .isEqualTo(new Planner.Compile("field hiding on an erased type: number")));
    }

    @Test
    void refusesStaticFieldsOfAnUninitializedClassBeforeReadingTheirValue() {
        final var vm = mock(VirtualMachine.class);
        final ClassType type = type(vm, "example.Untouched", null);
        final Field field = field(type, "counter");
        when(type.fields()).thenReturn(List.of(field));
        when(field.isStatic()).thenReturn(true);
        assertThat(Planner.plan(vm, null, Map.of(), ExpressionParser.parse("example.Untouched.counter")))
            .isEqualTo(new Planner.Compile("static field's class is not initialized: example.Untouched"));
        verify(type, never()).getValue(any());
    }

    @Test
    void anIntersectionCastChecksEveryBound() throws Exception {
        final VirtualMachine vm = mock(VirtualMachine.class);
        final InterfaceType first = mock(InterfaceType.class);
        final InterfaceType second = mock(InterfaceType.class);
        when(first.name()).thenReturn("example.First");
        when(second.name()).thenReturn("example.Second");
        when(vm.classesByName("example.First")).thenReturn(List.of(first));
        when(vm.classesByName("example.Second")).thenReturn(List.of(second));
        final ClassType concrete = type(vm, "example.Concrete", null);
        when(concrete.interfaces()).thenReturn(List.of(first));
        final ObjectReference value = mock(ObjectReference.class);
        when(value.type()).thenReturn(concrete);
        when(value.referenceType()).thenReturn(concrete);
        final var plan = Planner.plan(vm, null, Map.of("$value", value),
            ExpressionParser.parse("(example.First & example.Second) $value"));
        assertThat(plan).isInstanceOf(Planner.Navigate.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            Interpreter.execute((Planner.Navigate) plan, result -> result))
            .isInstanceOf(Interpreter.RaisedException.class).hasCauseInstanceOf(ClassCastException.class);
    }

    private static byte[] pool(String... names) throws Exception {
        final var bytes = new ByteArrayOutputStream();
        try (var data = new DataOutputStream(bytes)) {
            for (int index = 0; index < names.length; index++) {
                data.writeByte(1);
                data.writeUTF(names[index]);
                data.writeByte(7);
                data.writeShort(index * 2 + 1);
            }
        }
        return bytes.toByteArray();
    }

    private static ClassType type(VirtualMachine vm, String name, ClassLoaderReference loader) {
        final ClassType type = mock(ClassType.class);
        when(type.name()).thenReturn(name);
        when(type.virtualMachine()).thenReturn(vm);
        when(type.classLoader()).thenReturn(loader);
        when(vm.classesByName(name)).thenReturn(List.of(type));
        return type;
    }

    private static Field field(ReferenceType owner, String name) {
        final Field field = mock(Field.class);
        when(field.name()).thenReturn(name);
        when(field.declaringType()).thenReturn(owner);
        return field;
    }
}
