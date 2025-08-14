package org.cdpg.dx.essearch.model;

import org.cdpg.dx.database.elastic.model.QueryModel;

import java.util.List;
import java.util.Map;

public interface ElasticsearchQueryDecorator {
  Map<FilterType, List<QueryModel>> add();
}
