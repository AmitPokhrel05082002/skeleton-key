package dev.skeletonkey.record;

import java.util.List;

/** A page of records with the paging and sort details of the request (Req 7.10). */
public record RecordPage(List<RecordView> items, int page, int size, long total, String sort) {}
