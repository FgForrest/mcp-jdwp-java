package one.edee.mcp.jdwp.core;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Captures stack and local-variable facts while a tracepoint thread is suspended.
 */
public final class FrameSnapshotter {
    private final JdiValueSnapshotter valueSnapshotter;

    public FrameSnapshotter(JdiValueSnapshotter valueSnapshotter) {
        this.valueSnapshotter = valueSnapshotter;
    }

    public List<Map<String, Object>> capture(ThreadReference thread, int maxFrames, boolean locals) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            List<StackFrame> frames = thread.frames(0, Math.min(thread.frameCount(), maxFrames));
            for (int index = 0; index < frames.size(); index++) {
                StackFrame frame = frames.get(index);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("index", index);
                item.put("className", frame.location().declaringType().name());
                item.put("methodName", frame.location().method().name());
                item.put("line", frame.location().lineNumber());
                item.put("codeIndex", frame.location().codeIndex());
                if (locals && index == 0) {
                    item.put("locals", captureLocals(frame));
                    if (frame.thisObject() != null) {
                        item.put("this", valueSnapshotter.snapshot(frame.thisObject()));
                    }
                }
                result.add(item);
            }
        } catch (IncompatibleThreadStateException exception) {
            result.add(Map.of("$error", "Thread is not suspended: " + exception.getMessage()));
        }
        return result;
    }

    private Map<String, Object> captureLocals(StackFrame frame) {
        Map<String, Object> locals = new LinkedHashMap<>();
        try {
            List<LocalVariable> variables = frame.visibleVariables();
            Map<LocalVariable, Value> values = frame.getValues(variables);
            for (LocalVariable variable : variables) {
                locals.put(variable.name(), valueSnapshotter.snapshot(values.get(variable)));
            }
        } catch (AbsentInformationException noDebugInfo) {
            locals.put("$error", "LocalVariableTable is absent; compile target classes with debug information");
        }
        return locals;
    }
}
