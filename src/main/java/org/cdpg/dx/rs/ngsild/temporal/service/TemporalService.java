package org.cdpg.dx.rs.ngsild.temporal.service;

import io.vertx.core.Future;
import org.cdpg.dx.common.response.ResponseModel;
import org.cdpg.dx.rs.latest.model.GetRequestModel;

public interface TemporalService {
    Future<ResponseModel> getTemporalSearch(GetRequestModel getRequestModel);
}
