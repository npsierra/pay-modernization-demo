package com.carddemo.posttran;

import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * In-memory replacement for a VSAM KSDS: random reads and writes by key, with records
 * kept in key order like a KSDS unload.
 */
final class KeyedFile<T> {
    private final String name;
    private final Function<T, String> keyOf;
    private final TreeMap<String, T> records = new TreeMap<>();

    KeyedFile(String name, Function<T, String> keyOf) {
        this.name = name;
        this.keyOf = keyOf;
    }

    Optional<T> read(String key) {
        return Optional.ofNullable(records.get(key));
    }

    /** Fails like a KSDS WRITE with file status 22 when the key already exists. */
    void write(T record) {
        String key = keyOf.apply(record);
        if (records.putIfAbsent(key, record) != null) {
            throw new AbendException("ERROR WRITING TO " + name + ": DUPLICATE KEY " + key);
        }
    }

    Collection<T> records() {
        return Collections.unmodifiableCollection(records.values());
    }
}
