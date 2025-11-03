package org.cdpg.dx.essearch.service;

import io.vertx.core.Future;
import org.cdpg.dx.essearch.model.SearchQuery;
import org.cdpg.dx.essearch.model.SearchResultWithCount;
import org.cdpg.dx.essearch.model.TemporalQueryRequestModel;
import org.cdpg.dx.rs.ngsild.queryparams.NGSILDQueryParams;

public interface SearchService {
  Future<SearchResultWithCount> searchTemporalDataWithCountValidation(
      String index,
      TemporalQueryRequestModel temporalQueryRequestModel,
      String sortBy,
      String sortOrder);

  Future<SearchResultWithCount> searchAllDataWithCountValidation(
      String index, int size, int page, String sortBy, String sortOrder);

  Future<SearchResultWithCount> searchWithCountValidation(SearchQuery searchQuery, String index);

  Future<SearchResultWithCount> searchAllDataWithCountValidationWithoutSorting(
      String index, int size, int page);

  Future<SearchResultWithCount> getSearchTemporalEntityDataWithCountValidation(
      String index, NGSILDQueryParams ngsildQueryParams);

  Future<Integer> getSearchTemporalEntityDataOnlyCount(
      String index, NGSILDQueryParams ngsildQueryParams);
}
