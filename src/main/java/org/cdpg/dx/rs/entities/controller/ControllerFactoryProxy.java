package org.cdpg.dx.rs.entities.controller;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.cdpg.dx.apiserver.ApiController;
import org.cdpg.dx.common.URNGenerator;

import java.util.List;

public class ControllerFactoryProxy {

    public static List<ApiController> createControllers(
            Vertx vertx, JsonObject config, URNGenerator urnGenerator)
    {


        ApiController entitiesController = new EntitiesController();
        // TODO create other controllers

        return List.of(entitiesController);



    }

}
