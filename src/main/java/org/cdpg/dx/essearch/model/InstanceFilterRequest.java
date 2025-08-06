package org.cdpg.dx.essearch.model;

public class InstanceFilterRequest {
  private String instance;

  public InstanceFilterRequest(String instance) {
    this.instance = instance;
  }

  public String getInstance() {
    return instance;
  }

  public void setInstance(String instance) {
    this.instance = instance;
  }
}
