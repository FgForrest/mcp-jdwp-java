package one.edee.mcp.jdwp.collector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

final class JsonlTraceWriter implements Closeable {
    private final ObjectWriter compactWriter;
    private final BufferedWriter writer;

    JsonlTraceWriter(ObjectMapper mapper, Path output) throws IOException {
        this.compactWriter = mapper.writer().without(SerializationFeature.INDENT_OUTPUT);
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        this.writer = Files.newBufferedWriter(
            output, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE
        );
    }

    synchronized void write(Map<String, Object> event) throws IOException {
        writer.write(compactWriter.writeValueAsString(event));
        writer.newLine();
        writer.flush();
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }
}
