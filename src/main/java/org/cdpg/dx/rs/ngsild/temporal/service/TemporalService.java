package org.cdpg.dx.rs.ngsild.temporal.service;

import com.google.common.collect.Multimap;
import io.vertx.core.Future;
import org.cdpg.dx.common.response.ResponseModel;
import org.cdpg.dx.rs.latest.model.GetRequestModel;
import org.cdpg.dx.rs.ngsild.queryparams.NGSILDQueryParams;

public interface TemporalService {
    Future<ResponseModel> getTemporalSearch(NGSILDQueryParams ngsildQueryParams);
}
