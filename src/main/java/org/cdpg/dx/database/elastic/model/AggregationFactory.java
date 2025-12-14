package org.cdpg.dx.database.elastic.model;

import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import org.cdpg.dx.database.elastic.util.*;

public class AggregationFactory {
  // Delegate to the centralized util AggregationBuilder which contains the full implementations
  public static Aggregation createAggregation(QueryModel queryModel) {
    return AggregationBuilder.createAggregation(queryModel);
  }
}
