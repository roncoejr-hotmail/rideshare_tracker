package org.miosys.transport.domain;

import java.util.List;

/** A page of list results. Total is the full match count. */
public record Page<T>(List<T> content, int page, int size, long total) {}
