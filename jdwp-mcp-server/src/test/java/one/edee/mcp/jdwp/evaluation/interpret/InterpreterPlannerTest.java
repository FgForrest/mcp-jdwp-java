package one.edee.mcp.jdwp.evaluation.interpret;

import com.sun.jdi.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Checks navigation plans using controlled target type metadata. */
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

    /** Constant-pool names can trigger fallback while qualified lookup keeps the loader visibility check. */
    @Test
    void defersConstantPoolBindingsAndResolvesQualifiedTypesThroughTheFrameLoader() throws Exception {
        final VirtualMachine vm = mock(VirtualMachine.class);
        final ClassLoaderReference frameLoader = mock(ClassLoaderReference.class);
        final ClassLoaderReference parent = mock(ClassLoaderReference.class);
        final ClassType owner = type(vm, "app.Example", frameLoader);
        final ClassType one = type(vm, "first.Badge", parent);
        final ClassType two = type(vm, "second.Badge", parent);
        type(vm, "java.lang.Class", null);
        when(vm.canGetConstantPool()).thenReturn(true);
        when(owner.constantPool()).thenReturn(pool("first/Badge", "second/Badge"));
        when(owner.constantPoolCount()).thenReturn(5);
        when(frameLoader.visibleClasses()).thenReturn(List.of(one));

        final StackFrame frame = typeFrame(owner, mock(Method.class));
        final ExpressionParser.Parsed simple = ExpressionParser.parse("Badge.class");
        final Planner.Compile expected = new Planner.Compile("type name needs an import or the frame's package: Badge");
        assertThat(Planner.plan(vm, frame, Map.of(), simple)).isEqualTo(expected);
        assertThat(Planner.plan(vm, frame, Map.of(), ExpressionParser.parse("first.Badge.class")).kind())
            .isEqualTo(Planner.KindOfPlan.NAVIGATE);
        assertThat(new JdiTypes(vm, owner).resolve("second.Badge")).isNull();
        when(frameLoader.visibleClasses()).thenReturn(List.of(one, two));
        assertThat(Planner.plan(vm, frame, Map.of(), simple)).isEqualTo(expected);
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

    /** Rejects a deep, valid AST before syntax classification or planning can recurse through it. */
    @Test
    void routesDeepExpressionsToCompilation() {
        final ExpressionParser.Parsed parsed = ExpressionParser.parse("1 + ".repeat(10000) + "1 == 10001");
        assertThat(parsed.error()).isNull();
        assertThat(Planner.plan(mock(VirtualMachine.class), null, Map.of(), parsed))
            .isEqualTo(new Planner.Compile("expression too deep to interpret"));
        assertThat(SyntaxShape.classify(parsed)).isEqualTo(
            new SyntaxShape.Classification(SyntaxShape.Phase.COMPILED, "expression too deep to interpret"));
    }

    /** Counts field selections hidden inside a single ECJ qualified-name node. */
    @Test
    void routesDeepQualifiedNamesToCompilation() throws Exception {
        final VirtualMachine vm = mock(VirtualMachine.class);
        final ClassType node = type(vm, "app.Node", null);
        final Field next = field(node, "next");
        final ObjectReference receiver = mock(ObjectReference.class);
        when(node.fields()).thenReturn(List.of(next));
        when(next.type()).thenReturn(node);
        when(receiver.type()).thenReturn(node);
        when(receiver.referenceType()).thenReturn(node);
        final Map<String, Value> bindings = Map.of("node", receiver);

        assertThat(Planner.plan(vm, null, bindings, ExpressionParser.parse("node.next")).kind())
            .isEqualTo(Planner.KindOfPlan.NAVIGATE);

        final ExpressionParser.Parsed parsed = ExpressionParser.parse("node" + ".next".repeat(10_000));
        assertThat(parsed.error()).isNull();
        assertThat(Planner.plan(vm, null, bindings, parsed))
            .isEqualTo(new Planner.Compile("expression too deep to interpret"));
        assertThat(SyntaxShape.classify(parsed)).isEqualTo(
            new SyntaxShape.Classification(SyntaxShape.Phase.COMPILED, "expression too deep to interpret"));
    }

    /** Rejects isolated surrogate code units in either kind of literal before creating a mirror. */
    @ParameterizedTest
    @ValueSource(strings = {"\"\\uD800\"", "'\\uDC00'"})
    void routesUnpairedSurrogateLiteralsToCompilation(String source) {
        final VirtualMachine vm = mock(VirtualMachine.class);
        type(vm, "java.lang.String", null);
        assertThat(Planner.plan(vm, null, Map.of(), ExpressionParser.parse(source)))
            .isEqualTo(new Planner.Compile("literal contains an unpaired surrogate"));
    }

    /** Defers package bindings and ambiguous java.lang bindings for simple and dotted names. */
    @ParameterizedTest
    @ValueSource(strings = {"app", "java.lang"})
    void rejectsConflictingConstantPoolAndDefaultTypes(String defaultPackage) throws Exception {
        final VirtualMachine vm = mock(VirtualMachine.class);
        final ClassType owner = type(vm, "app.Example", null);
        type(vm, defaultPackage + ".Badge", null);
        type(vm, defaultPackage + ".Badge$Member", null);
        type(vm, "imported.Badge", null);
        type(vm, "java.lang.Class", null);
        when(vm.canGetConstantPool()).thenReturn(true);
        when(owner.constantPool()).thenReturn(pool("imported/Badge"));
        when(owner.constantPoolCount()).thenReturn(3);
        final StackFrame frame = typeFrame(owner, mock(Method.class));
        final String reason = defaultPackage.equals("app")
            ? "type name needs an import or the frame's package: Badge" : "ambiguous type name: Badge";
        assertThat(Planner.plan(vm, frame, Map.of(), ExpressionParser.parse("Badge.class")))
            .isEqualTo(new Planner.Compile(reason));
        assertThat(Planner.plan(vm, frame, Map.of(), ExpressionParser.parse("Badge.Member.class")))
            .isEqualTo(new Planner.Compile(reason));
        assertThat(Planner.plan(vm, frame, Map.of(), ExpressionParser.parse(defaultPackage + ".Badge.Member.class"))
            .kind()).isEqualTo(Planner.KindOfPlan.NAVIGATE);
    }

    /** Defers a loaded package type even if it would shadow a loaded java.lang type. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void defersSimplePackageTypes(boolean javaLangLoaded) {
        final VirtualMachine vm = mock(VirtualMachine.class);
        final ClassType owner = type(vm, "app.Example", null);
        type(vm, "app.Integer", null);
        type(vm, "java.lang.Class", null);
        if (javaLangLoaded) {
            type(vm, "java.lang.Integer", null);
        }
        final StackFrame frame = typeFrame(owner, mock(Method.class));
        assertThat(Planner.plan(vm, frame, Map.of(), ExpressionParser.parse("Integer.class")))
            .isEqualTo(new Planner.Compile("type name needs an import or the frame's package: Integer"));
        assertThat(Planner.plan(vm, frame, Map.of(), ExpressionParser.parse("app.Integer.class")).kind())
            .isEqualTo(Planner.KindOfPlan.NAVIGATE);
    }

    /** Allows a java.lang type when its constant-pool reference agrees and no package type is loaded. */
    @Test
    void navigatesAnUncontestedJavaLangType() throws Exception {
        final VirtualMachine vm = mock(VirtualMachine.class);
        final ClassType owner = type(vm, "app.Example", null);
        type(vm, "java.lang.Integer", null);
        type(vm, "java.lang.Class", null);
        when(vm.canGetConstantPool()).thenReturn(true);
        when(owner.constantPool()).thenReturn(pool("java/lang/Integer"));
        when(owner.constantPoolCount()).thenReturn(3);

        assertThat(Planner.plan(vm, typeFrame(owner, mock(Method.class)), Map.of(),
            ExpressionParser.parse("Integer.class")).kind()).isEqualTo(Planner.KindOfPlan.NAVIGATE);
    }

    /** Uses an array class constant as evidence of a competing reference component name. */
    @Test
    void detectsConstantPoolConflictsFromArrayComponents() throws Exception {
        final VirtualMachine vm = mock(VirtualMachine.class);
        final ClassType owner = type(vm, "app.Example", null);
        type(vm, "java.lang.Integer", null);
        type(vm, "java.lang.Class", null);
        when(vm.canGetConstantPool()).thenReturn(true);
        when(owner.constantPool()).thenReturn(pool("[[I"));
        when(owner.constantPoolCount()).thenReturn(3);
        final StackFrame frame = typeFrame(owner, mock(Method.class));
        final ExpressionParser.Parsed expression = ExpressionParser.parse("Integer.class");
        assertThat(Planner.plan(vm, frame, Map.of(), expression).kind()).isEqualTo(Planner.KindOfPlan.NAVIGATE);

        when(owner.constantPool()).thenReturn(pool("[[Lalias/Integer;", "[[I"));
        when(owner.constantPoolCount()).thenReturn(5);
        assertThat(Planner.plan(vm, frame, Map.of(), expression))
            .isEqualTo(new Planner.Compile("ambiguous type name: Integer"));
    }

    /** Checks a conflicting class name in the pool of a loaded enclosing type. */
    @Test
    void defersJavaLangWhenAnEnclosingPoolConflicts() throws Exception {
        final VirtualMachine vm = mock(VirtualMachine.class);
        final ClassType enclosing = type(vm, "app.Outer", null);
        final ClassType owner = type(vm, "app.Outer$Frame", null);
        type(vm, "java.lang.Integer", null);
        type(vm, "java.lang.Class", null);
        when(vm.canGetConstantPool()).thenReturn(true);
        when(enclosing.constantPool()).thenReturn(pool("imported/Integer"));
        when(enclosing.constantPoolCount()).thenReturn(3);

        assertThat(Planner.plan(vm, typeFrame(owner, mock(Method.class)), Map.of(),
            ExpressionParser.parse("Integer.class"))).isEqualTo(new Planner.Compile("ambiguous type name: Integer"));
    }

    /** Defers a raw simple binary name from the default package. */
    @Test
    void defersLastResortSimpleTypeNames() {
        final VirtualMachine vm = mock(VirtualMachine.class);
        final ClassType owner = type(vm, "app.Example", null);
        type(vm, "DefaultType", null);
        type(vm, "java.lang.Class", null);

        assertThat(Planner.plan(vm, typeFrame(owner, mock(Method.class)), Map.of(),
            ExpressionParser.parse("DefaultType.class")))
            .isEqualTo(new Planner.Compile("type name needs an import or the frame's package: DefaultType"));
    }

    /** An array mirror alone cannot establish a simple component name's binding. */
    @Test
    void defersAnArrayWithoutAProvenSimpleComponentType() {
        final VirtualMachine vm = mock(VirtualMachine.class);
        final ClassType owner = type(vm, "app.Example", null);
        final ArrayType array = mock(ArrayType.class);
        type(vm, "java.lang.Class", null);
        when(vm.classesByName("Badge[]")).thenReturn(List.of(array));

        assertThat(Planner.plan(vm, typeFrame(owner, mock(Method.class)), Map.of(),
            ExpressionParser.parse("Badge[].class")))
            .isEqualTo(new Planner.Compile("type is not loaded or visible: Badge"));
    }

    /** Keeps lexical bindings for both the frame's class and its member type. */
    @Test
    void navigatesTheFrameTypeAndItsMember() {
        final VirtualMachine vm = mock(VirtualMachine.class);
        final ClassType owner = type(vm, "app.Example", null);
        type(vm, "app.Example$Badge", null);
        type(vm, "app.Badge", null);
        type(vm, "java.lang.Class", null);
        final StackFrame frame = typeFrame(owner, mock(Method.class));

        assertThat(Planner.plan(vm, frame, Map.of(), ExpressionParser.parse("Example.class")).kind())
            .isEqualTo(Planner.KindOfPlan.NAVIGATE);
        assertThat(Planner.plan(vm, frame, Map.of(), ExpressionParser.parse("Badge.class")).kind())
            .isEqualTo(Planner.KindOfPlan.NAVIGATE);
    }

    /** Includes a matching member type inherited from a superinterface before default lookup. */
    @Test
    void resolvesInheritedInterfaceMemberType() {
        final VirtualMachine vm = mock(VirtualMachine.class);
        final ClassType owner = type(vm, "app.Example", null);
        final InterfaceType parent = mock(InterfaceType.class);
        when(parent.name()).thenReturn("app.Contract");
        when(owner.interfaces()).thenReturn(List.of(parent));
        final ClassType inherited = type(vm, "app.Contract$Badge", null);
        type(vm, "java.lang.Badge", null);

        assertThat(new JdiTypes(vm, owner).resolve("Badge")).isSameAs(inherited);
    }

    /** Searches the member scope before considering the frame class's own simple name. */
    @Test
    void inheritedMemberShadowsTheFrameClassName() {
        final VirtualMachine vm = mock(VirtualMachine.class);
        final ClassType owner = type(vm, "app.SelfFrame", null);
        final ClassType base = type(vm, "app.Base", null);
        final ClassType member = type(vm, "app.Base$SelfFrame", null);
        when(owner.superclass()).thenReturn(base);

        assertThat(new JdiTypes(vm, owner).resolve("SelfFrame")).isSameAs(member);
    }

    /** An unloaded declaration in the current or enclosing scope still hides a loaded inherited type. */
    @ParameterizedTest
    @CsvSource({"app.Example", "app.Example$Inner"})
    void routesUnloadedHidingMemberTypesToCompilation(String frameType) throws Exception {
        final VirtualMachine vm = mock(VirtualMachine.class);
        final ClassType owner = type(vm, "app.Example", null);
        final ClassType base = type(vm, "app.Base", null);
        final ClassType hidden = type(vm, "app.Base$Badge", null);
        type(vm, "java.lang.Class", null);
        when(owner.superclass()).thenReturn(base);
        when(vm.canGetConstantPool()).thenReturn(true);
        when(owner.constantPool()).thenReturn(pool("app/Example$Badge"));
        when(owner.constantPoolCount()).thenReturn(3);

        final Field limit = field(hidden, "LIMIT");
        final IntegerType intType = mock(IntegerType.class);
        when(intType.name()).thenReturn("int");
        when(limit.type()).thenReturn(intType);
        when(limit.isStatic()).thenReturn(true);
        when(hidden.fields()).thenReturn(List.of(limit));
        when(hidden.isInitialized()).thenReturn(true);

        final ClassType declaring = frameType.equals("app.Example") ? owner : type(vm, frameType, null);
        final StackFrame frame = mock(StackFrame.class);
        final Location location = mock(Location.class);
        when(location.declaringType()).thenReturn(declaring);
        when(frame.location()).thenReturn(location);

        final Planner.Compile expected = new Planner.Compile("type is not loaded: app.Example$Badge");
        assertThat(Planner.plan(vm, frame, Map.of(), ExpressionParser.parse("Badge.class"))).isEqualTo(expected);
        assertThat(Planner.plan(vm, frame, Map.of(), ExpressionParser.parse("Badge.LIMIT"))).isEqualTo(expected);
    }

    /** Refuses a missing member of a bound qualifier even when a hidden qualifier has that member. */
    @Test
    void routesMissingQualifiedMemberToCompilation() {
        final VirtualMachine vm = mock(VirtualMachine.class);
        final ClassType owner = type(vm, "app.Example", null);
        final ClassType base = type(vm, "app.Base", null);
        type(vm, "app.Example$Scope", null);
        type(vm, "app.Base$Scope", null);
        type(vm, "app.Base$Scope$Badge", null);
        type(vm, "java.lang.Class", null);
        when(owner.superclass()).thenReturn(base);
        final StackFrame frame = mock(StackFrame.class);
        final Location location = mock(Location.class);
        when(location.declaringType()).thenReturn(owner);
        when(frame.location()).thenReturn(location);

        assertThat(Planner.plan(vm, frame, Map.of(), ExpressionParser.parse("Scope.Badge.class")))
            .isEqualTo(new Planner.Compile("member type is unavailable: app.Example$Scope.Badge"));
    }

    /** A loaded member can be selected from an unloaded enclosing type, but lookup cannot skip that scope. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void stopsLookupAtAnUnloadedEnclosingType(boolean memberLoaded) {
        final VirtualMachine vm = mock(VirtualMachine.class);
        final ClassType owner = type(vm, "app.Outer$Frame", null);
        type(vm, "java.lang.Class", null);
        type(vm, "app.Marker", null);
        if (memberLoaded) {
            final ClassType member = type(vm, "app.Outer$Marker", null);
            assertThat(new JdiTypes(vm, owner).resolve("Marker")).isSameAs(member);
        } else {
            assertThat(Planner.plan(vm, typeFrame(owner, mock(Method.class)), Map.of(),
                ExpressionParser.parse("Marker.class")))
                .isEqualTo(new Planner.Compile("enclosing type is not loaded: app.Outer"));
        }
    }

    /** A local or anonymous scope cannot bypass its unavailable enclosing method. */
    @ParameterizedTest
    @ValueSource(strings = {"Outer$1Frame", "Outer$1", "Outer$1Frame$Inner"})
    void defersLocalClassBindingsAtAnUnloadedEnclosingType(String binary) {
        final VirtualMachine vm = mock(VirtualMachine.class);
        final ClassType owner = type(vm, "app." + binary, null);
        if (binary.endsWith("$Inner")) {
            type(vm, "app.Outer$1Frame", null);
        }
        type(vm, "app.Outer$Marker", null);
        type(vm, "java.lang.Class", null);

        assertThat(Planner.plan(vm, typeFrame(owner, mock(Method.class)), Map.of(),
            ExpressionParser.parse("Marker.class")))
            .isEqualTo(new Planner.Compile("enclosing method is not visible: app.Outer"));
        verify(vm, never()).classesByName("app.Outer$Marker");
    }

    /** A package prefix can bypass the unloaded enclosing scope of a local class. */
    @Test
    void navigatesPackageQualifiedTypesFromADetachedLocalClass() {
        final VirtualMachine vm = mock(VirtualMachine.class);
        final ClassType owner = type(vm, "app.Outer$1Frame", null);
        type(vm, "app.Outer$java", null);
        type(vm, "java.lang.String", null);
        type(vm, "java.lang.Class", null);

        assertThat(Planner.plan(vm, typeFrame(owner, mock(Method.class)), Map.of(),
            ExpressionParser.parse("java.lang.String.class")).kind()).isEqualTo(Planner.KindOfPlan.NAVIGATE);
        verify(vm, never()).classesByName("app.Outer$java");
    }

    /** A member-class frame can still bind a loaded sibling of its unloaded enclosing type. */
    @Test
    void navigatesLoadedSiblingMembersOfAnUnloadedEnclosingType() {
        final VirtualMachine vm = mock(VirtualMachine.class);
        final ClassType owner = type(vm, "app.Outer$Builder", null);
        final ClassType sibling = type(vm, "app.Outer$Kind", null);
        type(vm, "java.lang.Class", null);

        assertThat(Planner.plan(vm, typeFrame(owner, mock(Method.class)), Map.of(),
            ExpressionParser.parse("Kind.class")).kind()).isEqualTo(Planner.KindOfPlan.NAVIGATE);
        assertThat(new JdiTypes(vm, owner).resolve("Kind")).isSameAs(sibling);
    }

    /** Allows package-qualified navigation while simple and uppercase qualifiers still require lexical metadata. */
    @Test
    void navigatesQualifiedNamesAtAnUnloadedEnclosingType() throws Exception {
        final VirtualMachine vm = mock(VirtualMachine.class);
        final ClassType owner = type(vm, "app.Outer$Frame", null);
        final ClassType integer = type(vm, "java.lang.Integer", null);
        type(vm, "java.lang.String", null);
        type(vm, "java.lang.Class", null);
        final IntegerType intType = mock(IntegerType.class);
        when(intType.name()).thenReturn("int");
        final Field maxValue = field(integer, "MAX_VALUE");
        when(maxValue.isStatic()).thenReturn(true);
        when(maxValue.type()).thenReturn(intType);
        when(integer.isInitialized()).thenReturn(true);
        when(integer.fields()).thenReturn(List.of(maxValue));
        final StackFrame frame = typeFrame(owner, mock(Method.class));

        for (String source : List.of("java.lang.String.class", "java.lang.Integer.MAX_VALUE")) {
            assertThat(Planner.plan(vm, frame, Map.of(), ExpressionParser.parse(source)).kind()).as(source)
                .isEqualTo(Planner.KindOfPlan.NAVIGATE);
        }
        for (String source : List.of("String.class", "String.Inner.class", "java.class")) {
            assertThat(Planner.plan(vm, frame, Map.of(), ExpressionParser.parse(source))).as(source)
                .isEqualTo(new Planner.Compile("enclosing type is not loaded: app.Outer"));
        }
    }

    /** Finds formal names after nested generic and interface-only bounds in all three supported scopes. */
    @ParameterizedTest
    @ValueSource(strings = {"method", "declaring", "enclosing"})
    void defersTypeParametersThatShadowLoadedTypes(String scope) {
        final VirtualMachine vm = mock(VirtualMachine.class);
        final ClassType enclosing = type(vm, "app.Outer", null);
        final ClassType owner = type(vm, "app.Outer$Frame", null);
        type(vm, "app.Outer$Frame$Marker", null);
        type(vm, "app.Outer$Frame$Marker$Part", null);
        type(vm, "java.lang.Class", null);
        final Method method = mock(Method.class);
        final String formals = "<Other::Ljava/util/List<+Ljava/util/List<Ljava/lang/String;>;>;"
            + "Next:TOther;Marker:Ljava/lang/Object;:Ljava/io/Serializable;>";
        switch (scope) {
            case "method" -> when(method.genericSignature()).thenReturn(formals + "()V");
            case "declaring" -> when(owner.genericSignature()).thenReturn(formals + "Ljava/lang/Object;");
            case "enclosing" -> when(enclosing.genericSignature()).thenReturn(formals + "Ljava/lang/Object;");
            default -> throw new AssertionError(scope);
        }
        final StackFrame frame = typeFrame(owner, method);
        assertThat(Planner.plan(vm, frame, Map.of(), ExpressionParser.parse("Marker.Part.class")))
            .isEqualTo(new Planner.Compile("type parameter may shadow type: Marker"));
    }

    /** Detects possible type parameters of another declared method from ordinary and synthetic frames. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void defersTypeParametersOfOtherMethods(boolean synthetic) {
        final VirtualMachine vm = mock(VirtualMachine.class);
        final ClassType owner = type(vm, "app.Example", null);
        type(vm, "app.Example$Marker", null);
        type(vm, "java.lang.Class", null);
        final Method frameMethod = mock(Method.class);
        when(frameMethod.name()).thenReturn(synthetic ? "lambda$enter$0" : "inspect");
        when(frameMethod.isSynthetic()).thenReturn(synthetic);
        final Method generic = mock(Method.class);
        when(generic.genericSignature()).thenReturn("<Marker:Ljava/lang/Object;>()V");
        when(owner.methods()).thenReturn(List.of(frameMethod, generic));

        assertThat(Planner.plan(vm, typeFrame(owner, frameMethod), Map.of(), ExpressionParser.parse("Marker.class")))
            .isEqualTo(new Planner.Compile("type parameter may shadow type: Marker"));
    }

    /** A malformed formal list defers lookup even if its apparent first name does not match. */
    @ParameterizedTest
    @ValueSource(strings = {
        "<Other:Ljava/util/List<Ljava/lang/String;>;()V", "<Other:I>()V", "<Other::>()V", "<Other>()V"
    })
    void defersTypeLookupWhenTheFormalListIsMalformed(String signature) {
        final VirtualMachine vm = mock(VirtualMachine.class);
        final ClassType owner = type(vm, "app.Example", null);
        type(vm, "app.Marker", null);
        type(vm, "java.lang.Class", null);
        final Method method = mock(Method.class);
        when(method.genericSignature()).thenReturn(signature);

        assertThat(Planner.plan(vm, typeFrame(owner, method), Map.of(), ExpressionParser.parse("Marker.class")))
            .isEqualTo(new Planner.Compile("cannot read formal type parameters"));
    }

    /** Defers a possible local class found in the declaring or enclosing type's constant pool. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void defersLocalClassesThatShadowLoadedTypes(boolean declaredHere) throws Exception {
        final VirtualMachine vm = mock(VirtualMachine.class);
        final ClassType enclosing = type(vm, "app.Outer", null);
        final ClassType owner = type(vm, "app.Outer$Frame", null);
        type(vm, "app.Marker", null);
        type(vm, "java.lang.Class", null);
        final ClassType scope = declaredHere ? owner : enclosing;
        when(vm.canGetConstantPool()).thenReturn(true);
        final String local = scope.name().replace('.', '/') + "$12Marker";
        when(scope.constantPool()).thenReturn(pool(local));
        when(scope.constantPoolCount()).thenReturn(3);

        assertThat(Planner.plan(vm, typeFrame(owner, mock(Method.class)), Map.of(),
            ExpressionParser.parse("Marker.class")))
            .isEqualTo(new Planner.Compile("local class may shadow type: Marker"));
    }

    /** Supplies the declaring type and method metadata of a stopped frame. */
    private static StackFrame typeFrame(ReferenceType owner, Method method) {
        final StackFrame frame = mock(StackFrame.class);
        final Location location = mock(Location.class);
        when(location.declaringType()).thenReturn(owner);
        when(location.method()).thenReturn(method);
        when(frame.location()).thenReturn(location);
        return frame;
    }

    /** An inaccessible declaration still hides a declaration above it before package or java.lang checks. */
    @ParameterizedTest
    @CsvSource({"false, false", "true, false", "false, true", "true, true"})
    void doesNotInheritHiddenOrInaccessibleMemberTypes(boolean privateMember, boolean packageTypeLoaded) {
        final VirtualMachine vm = mock(VirtualMachine.class);
        final ClassType owner = type(vm, "app.Example", null);
        final ClassType parent = type(vm, "outer.Parent", null);
        final ClassType grandparent = type(vm, "outer.Grandparent", null);
        final ClassType shadow = type(vm, "outer.Parent$Badge", null);
        type(vm, "outer.Grandparent$Badge", null);
        final ClassType standard = type(vm, "java.lang.Badge", null);
        type(vm, "java.lang.Class", null);
        when(owner.superclass()).thenReturn(parent);
        when(parent.superclass()).thenReturn(grandparent);
        when(shadow.isPrivate()).thenReturn(privateMember);
        when(shadow.isPackagePrivate()).thenReturn(!privateMember);

        if (packageTypeLoaded) {
            type(vm, "app.Badge", null);
            assertThat(Planner.plan(vm, typeFrame(owner, mock(Method.class)), Map.of(),
                ExpressionParser.parse("Badge.class")))
                .isEqualTo(new Planner.Compile("type name needs an import or the frame's package: Badge"));
        } else {
            assertThat(new JdiTypes(vm, owner).resolve("Badge")).isSameAs(standard);
        }
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
