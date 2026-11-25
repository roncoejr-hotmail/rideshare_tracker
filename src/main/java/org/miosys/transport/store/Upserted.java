package org.miosys.transport.store;

/** Result of a PUT: whether the row was created, plus the stored row. */
public record Upserted<T>(boolean created, T value) {}
