package dev.skeletonkey.record;

import java.util.List;

/** A page of {@link RecordItem}s with the paging and sort details of the request. */
public record RecordItemPage(List<RecordItem> items, int page, int size, long total, String sort) {}
