package org.cdpg.dx.rs.ngsild.temporal.service;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.common.response.ResponseModel;
import org.cdpg.dx.essearch.service.SearchService;
import org.cdpg.dx.rs.indexgenerator.IndexNameCreation;
import org.cdpg.dx.rs.ngsild.queryparams.NGSILDQueryParams;

public class NGSILDServiceImpl implements NGSILDService {
  private static final Logger LOGGER = LogManager.getLogger(NGSILDServiceImpl.class);
  private final SearchService searchService;

  public NGSILDServiceImpl(SearchService searchService) {
    this.searchService = searchService;
  }

  @Override
  public Future<ResponseModel> getTemporalSearchData(NGSILDQueryParams ngsildQueryParams) {
    LOGGER.debug("NGSILDQueryParams: {}", ngsildQueryParams.toString());
    String index = IndexNameCreation.createIndex(ngsildQueryParams.getId().get(0).toString());
    return searchService
        .getSearchTemporalEntityDataWithCountValidation(index, ngsildQueryParams)
        .map(
            searchResultWithCount -> {
              LOGGER.debug(
                  "Successfully fetched count for ID: {}",
                  ngsildQueryParams.getId().get(0).toString());
              // If client requested aggregatedValues (either via format or options), return
              // aggregations instead of document hits. The ElasticsearchServiceImpl already
              // parsed aggregations into ElasticsearchResponse.getAggregations(). Use the
              // ResponseModel constructor that populates the response with aggregations.
              String fmt = ngsildQueryParams.getFormat();
              String opts = ngsildQueryParams.getOptions();
              boolean wantsAggregated = false;
              if (fmt != null && fmt.equalsIgnoreCase("aggregatedValues")) wantsAggregated = true;
              if (opts != null && opts.toLowerCase().contains("aggregatedvalues"))
                wantsAggregated = true;
              if (wantsAggregated) {
                return new ResponseModel(searchResultWithCount.getResults());
              }

              // Default behavior: return paginated hits
              return new ResponseModel(
                  searchResultWithCount.getResults(),
                  ngsildQueryParams.getPageSize(),
                  ngsildQueryParams.getPageFrom());
            })
        .onFailure(
            err -> {
              LOGGER.error(
                  "Error fetching data for ID: {}",
                  ngsildQueryParams.getId().get(0).toString(),
                  err);
            });
  }

  @Override
  public Future<Integer> getTemporalSearchCount(NGSILDQueryParams ngsildQueryParams) {
    LOGGER.debug("NGSILDQueryParams: {}", ngsildQueryParams.toString());
    String index = IndexNameCreation.createIndex(ngsildQueryParams.getId().get(0).toString());
    Promise<Integer> promise = Promise.promise();
    searchService
        .getSearchTemporalEntityDataOnlyCount(index, ngsildQueryParams)
        .onSuccess(promise::complete)
        .onFailure(
            err -> {
              LOGGER.error(
                  "Error fetching count for ID: {}",
                  ngsildQueryParams.getId().get(0).toString(),
                  err);
            });
    return promise.future();
  }

  @Override
  public Future<ResponseModel> getEntitiesAttributeSearchData(NGSILDQueryParams ngsildQueryParams) {
    LOGGER.debug("NGSILDQueryParams: {}", ngsildQueryParams.toString());
    String index = IndexNameCreation.createIndex(ngsildQueryParams.getId().get(0).toString());
    return searchService
        .getSearchEntitiesAttributeDataWithCountValidation(index, ngsildQueryParams)
        .map(
            searchResultWithCount -> {
              LOGGER.debug(
                  "Successfully fetched count for ID: {}",
                  ngsildQueryParams.getId().get(0).toString());
              return new ResponseModel(
                  searchResultWithCount.getResults(),
                  ngsildQueryParams.getPageSize(),
                  ngsildQueryParams.getPageFrom());
            })
        .onFailure(
            err -> {
              LOGGER.error(
                  "Error fetching data for ID: {}",
                  ngsildQueryParams.getId().get(0).toString(),
                  err);
            });
  }

  @Override
  public Future<Integer> getEntitiesAttributeSearchCount(NGSILDQueryParams ngsildQueryParams) {
    LOGGER.debug("NGSILDQueryParams: {}", ngsildQueryParams.toString());
    String index = IndexNameCreation.createIndex(ngsildQueryParams.getId().get(0).toString());
    Promise<Integer> promise = Promise.promise();
    searchService
        .getSearchEntitiesAttributeDataOnlyCount(index, ngsildQueryParams)
        .onSuccess(promise::complete)
        .onFailure(
            err -> {
              LOGGER.error(
                  "Error fetching count for ID: {}",
                  ngsildQueryParams.getId().get(0).toString(),
                  err);
            });
    return promise.future();
  }
}
