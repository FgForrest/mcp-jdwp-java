package one.edee.mcp.jdwp.evaluation;

import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class InterpreterCorpus {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    record Entry(String id, String tool, String param, String expression, @Nullable String stop,
                 @Nullable String expectedClass, @Nullable String notes) {
    }

    static List<Entry> read(Reader source, String name) throws IOException {
        final var lines = new BufferedReader(source);
        final List<Entry> entries = new ArrayList<>();
        final Set<String> ids = new HashSet<>();
        int lineNumber = 0;
        String line;
        while ((line = lines.readLine()) != null) {
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }
            try {
                final JsonNode row = JSON.readTree(line);
                if (!row.isObject()) {
                    throw new IllegalArgumentException("expected a JSON object");
                }
                final String id = requiredText(row, "id");
                if (!ids.add(id)) {
                    throw new IllegalArgumentException("duplicate id " + id);
                }
                entries.add(new Entry(id, requiredText(row, "tool"), requiredText(row, "param"),
                    requiredText(row, "expression"), optionalText(row, "stop"),
                    optionalText(row, "expected_class"), optionalText(row, "notes")));
            } catch (IllegalArgumentException | JacksonException e) {
                throw new IllegalArgumentException(name + ":" + lineNumber + ": " + e.getMessage(), e);
            }
        }
        return List.copyOf(entries);
    }

    private static String requiredText(JsonNode row, String field) {
        final String text = optionalText(row, field);
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return text;
    }

    private static @Nullable String optionalText(JsonNode row, String field) {
        final JsonNode value = row.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return value.stringValue();
    }

    private InterpreterCorpus() {
    }
}
