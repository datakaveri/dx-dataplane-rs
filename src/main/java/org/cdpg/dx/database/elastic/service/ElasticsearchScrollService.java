package org.cdpg.dx.database.elastic.service;

import io.vertx.core.Future;
import org.cdpg.dx.database.elastic.model.QueryModel;
import org.cdpg.dx.database.elastic.model.ScrollResult;

public interface ElasticsearchScrollService {
    /**
     * Scroll search for large result sets.
     */
    Future<ScrollResult> scrollSearch(String index, QueryModel queryModel, String scrollTimeout, String options);

    /**
     * Continue scroll search with scrollId.
     */
    Future<ScrollResult> continueScroll(String scrollId, String scrollTimeout);

    /**
     * Clear scroll context.
     */
    Future<Void> clearScroll(String scrollId);

    /**
     * REST-based scroll search for large result sets.
     */
    Future<ScrollResult> scrollSearchRest(String index, QueryModel queryModel, String scrollTimeout, String options);

    /**
     * Continue REST-based scroll search with scrollId.
     */
    Future<ScrollResult> continueScrollRest(String scrollId, String scrollTimeout);

    /**
     * Clear REST-based scroll context.
     */
    Future<Void> clearScrollRest(String scrollId);
}
