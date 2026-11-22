package one.edee.mcp.jdwp.evaluation.interpret;

import org.eclipse.jdt.internal.compiler.ast.ASTNode;
import org.eclipse.jdt.internal.compiler.ast.QualifiedNameReference;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounds ECJ trees before either an ECJ visitor or navigation recurses through them. */
final class AstDepth {
    /** Maximum supported depth, including each segment of a qualified name. */
    private static final int MAX_DEPTH = 256;
    /** Diagnostic shared by routing and syntax-only classification. */
    static final String REASON = "expression too deep to interpret";

    /** ECJ node fields that can hold syntax children, including arrays of children. */
    private static final ClassValue<List<Field>> CHILDREN = new ClassValue<>() {
        /** Finds child fields without relying on ECJ's recursive visitor. */
        @Override
        protected List<Field> computeValue(Class<?> type) {
            final List<Field> fields = new ArrayList<>();
            for (Class<?> parent = type; parent != null && ASTNode.class.isAssignableFrom(parent);
                    parent = parent.getSuperclass()) {
                for (Field field : parent.getDeclaredFields()) {
                    Class<?> element = field.getType();
                    while (element.isArray()) {
                        element = element.componentType();
                    }
                    if (ASTNode.class.isAssignableFrom(element) && !Modifier.isStatic(field.getModifiers())) {
                        field.setAccessible(true);
                        fields.add(field);
                    }
                }
            }
            return fields;
        }
    };

    /** One entry or exit in the iterative depth-first walk. */
    private record Visit(ASTNode node, int depth, boolean exit) {}

    /** This class provides only a static depth check. */
    private AstDepth() {}

    /** Returns whether a path exceeds the limit, ignoring ECJ's links back to ancestor nodes. */
    static boolean exceedsLimit(ExpressionParser.Parsed parsed) {
        final Deque<Visit> visits = new ArrayDeque<>();
        push(visits, parsed.expression(), 1);
        if (parsed.statements() != null) {
            for (ASTNode statement : parsed.statements()) {
                push(visits, statement, 1);
            }
        }
        final Set<ASTNode> path = Collections.newSetFromMap(new IdentityHashMap<>());
        final Map<ASTNode, Integer> depths = new IdentityHashMap<>();
        while (!visits.isEmpty()) {
            final Visit visit = visits.pop();
            final ASTNode node = visit.node();
            if (visit.exit()) {
                path.remove(node);
                continue;
            }
            if (path.contains(node) || depths.getOrDefault(node, 0) >= visit.depth()) {
                continue;
            }
            if (visit.depth() > MAX_DEPTH) {
                return true;
            }
            path.add(node);
            depths.put(node, visit.depth());
            visits.push(new Visit(node, visit.depth(), true));
            for (Field field : CHILDREN.get(node.getClass())) {
                try {
                    push(visits, field.get(node), visit.depth() + 1);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("Cannot inspect an ECJ syntax child", e);
                }
            }
        }
        return false;
    }

    /** Queues AST children, counting qualified-name segments that become nested reads during planning. */
    private static void push(Deque<Visit> visits, @Nullable Object children, int depth) {
        if (children instanceof QualifiedNameReference name) {
            visits.push(new Visit(name, depth + name.tokens.length - 1, false));
        } else if (children instanceof ASTNode node) {
            visits.push(new Visit(node, depth, false));
        } else if (children instanceof Object[] array) {
            for (Object child : array) {
                push(visits, child, depth);
            }
        }
    }
}
