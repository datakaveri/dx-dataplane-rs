package org.cdpg.dx.rs.ngsild.temporal.service;

import io.vertx.core.Future;
import org.cdpg.dx.common.response.ResponseModel;
import org.cdpg.dx.rs.ngsild.queryparams.NGSILDQueryParams;

public interface NGSILDService {
  Future<ResponseModel> getTemporalSearchData(NGSILDQueryParams ngsildQueryParams);

  Future<Integer> getTemporalSearchCount(NGSILDQueryParams ngsildQueryParams);

  Future<ResponseModel> getEntitiesAttributeSearchData(NGSILDQueryParams ngsildQueryParams);

  Future<Integer> getEntitiesAttributeSearchCount(NGSILDQueryParams ngsildQueryParams);
}
