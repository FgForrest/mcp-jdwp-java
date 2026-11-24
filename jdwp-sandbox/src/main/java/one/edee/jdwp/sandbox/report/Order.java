package one.edee.jdwp.sandbox.report;

import java.time.Instant;

/** An order identified by its order number and creation time. */
public record Order(long number, Instant createdAt) {
}
