package org.cdpg.dx.rs.ngsild.temporal.factory;

import org.cdpg.dx.rs.ngsild.temporal.controller.TemporalController;

public class TemporalControllerFactory {
    public static TemporalController create(){
        return new TemporalController();
    }
}
