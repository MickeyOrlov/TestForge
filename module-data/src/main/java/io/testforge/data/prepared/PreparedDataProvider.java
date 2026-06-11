package io.testforge.data.prepared;

import java.util.List;

/**
 * SPI: knows how to bring one domain object into the requested state. This is
 * THE adaptation point of the pool — in a real project the implementation
 * drives the product API (often a module-flow run) until the object reaches
 * the state described by the tags.
 *
 * <p>Implementations must produce objects safe to hand to exactly one test:
 * unique identifiers, no shared mutable fixtures.
 */
public interface PreparedDataProvider<T> {

    Class<T> type();

    T prepare(List<String> tags);
}
