package org.cdpg.dx.rs.validation.ngsild;

import io.vertx.core.MultiMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class TemporalEntitiesValidation {
  private static final Logger LOGGER = LogManager.getLogger(TemporalEntitiesValidation.class);

  private MultiMap params;

    public TemporalEntitiesValidation(MultiMap params) {
        this.params = params;
    }

    public void validate(){

    }

}
