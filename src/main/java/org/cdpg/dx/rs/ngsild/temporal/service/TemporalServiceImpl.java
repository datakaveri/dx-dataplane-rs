package org.cdpg.dx.rs.ngsild.temporal.service;

import io.vertx.core.Future;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.common.response.ResponseModel;
import org.cdpg.dx.rs.ngsild.queryparams.NGSILDQueryParams;

public class TemporalServiceImpl implements TemporalService {
  private static final Logger LOGGER = LogManager.getLogger(TemporalServiceImpl.class);

  @Override
  public Future<ResponseModel> getTemporalSearch(NGSILDQueryParams ngsildQueryParams) {

    LOGGER.debug("NGSILDQueryParams: {}", ngsildQueryParams.toString());


    return null;
  }

}
