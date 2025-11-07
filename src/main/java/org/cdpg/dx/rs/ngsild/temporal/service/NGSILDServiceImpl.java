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
  /*private final NewQueryMapper queryMapper;*/

  private final SearchService searchService;

  public NGSILDServiceImpl(SearchService searchService) {
    this.searchService = searchService;
    /*this.queryMapper = new NewQueryMapper();*/
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
