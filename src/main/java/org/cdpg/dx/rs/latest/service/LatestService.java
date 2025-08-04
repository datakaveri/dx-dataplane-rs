package org.cdpg.dx.rs.latest.service;

import io.vertx.core.Future;
import org.cdpg.dx.common.response.ResponseModel;
import org.cdpg.dx.rs.latest.model.LatestData;

public interface LatestService {

    Future<ResponseModel> getLatestData(String rsId, int size, int page, String time, String endTime, String timeRel);
    Future<ResponseModel> getLatestData(String rsId, int size, int page);
}
