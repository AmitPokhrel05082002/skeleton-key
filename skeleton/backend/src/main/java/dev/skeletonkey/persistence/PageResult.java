package dev.skeletonkey.persistence;

import java.util.List;

/** One page of a repository query. */
public record PageResult<T>(List<T> items, int page, int size, long total) {}
