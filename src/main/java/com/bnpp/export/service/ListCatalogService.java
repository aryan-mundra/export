package com.bnpp.export.service;

import com.bnpp.export.dto.ListPageResponse;
import com.bnpp.export.dto.SubscriberPageResponse;

/**
 * Searchable, paged catalogs for the Export page, so it never loads every list or subscriber.
 *
 * Lists: one row per list (its newest certified version operators can export), searched by name or
 * code in the database. Subscribers: searched and paged on the server.
 */
public interface ListCatalogService {

    /** @param sort "recent" (last exported first, then by name) or "name" */
    ListPageResponse lists(String query, String sort, int offset, int limit);

    /**
     * Active subscribers matching a search (name, business line or region), optionally of one format
     * or one template, a page at a time.
     */
    SubscriberPageResponse subscribers(String query, String format, Long templateFamilyId, int offset, int limit);
}
