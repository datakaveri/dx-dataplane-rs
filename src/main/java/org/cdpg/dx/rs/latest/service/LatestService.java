package org.cdpg.dx.rs.latest.service;

import io.vertx.core.Future;
import org.cdpg.dx.common.response.ResponseModel;
import org.cdpg.dx.essearch.model.SearchQuery;
import org.cdpg.dx.rs.latest.model.GetRequestModel;

public interface LatestService {
  Future<ResponseModel> postSearch(SearchQuery searchQuery, String id);

  Future<ResponseModel> getSearch(GetRequestModel getRequestModel);
}
