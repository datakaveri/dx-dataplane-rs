package org.cdpg.dx.rs.audit.util;

import java.util.ArrayList;
import java.util.Arrays;

public class Constants {
  /** Item types. */
  public static final String ITEM_TYPE_RESOURCE = "iudx:Resource";

  public static final String ITEM_TYPE_RESOURCE_GROUP = "iudx:ResourceGroup";
  public static final String ITEM_TYPE_RESOURCE_SERVER = "iudx:ResourceServer";
  public static final String ITEM_TYPE_PROVIDER = "iudx:Provider";
  public static final String ITEM_TYPE_COS = "iudx:COS";
  public static final String ITEM_TYPE_OWNER = "iudx:Owner";
  public static final String ITEM_TYPE_INSTANCE = "iudx:Instance";
  public static final String ITEM_TYPE_AI_MODEL = "adex:AiModel";
  public static final String ITEM_TYPE_DATA_BANK = "adex:DataBank";
  public static final String ITEM_TYPE_APPS = "adex:Apps";
  public static final ArrayList<String> ITEM_TYPES =
      new ArrayList<String>(
          Arrays.asList(
              ITEM_TYPE_RESOURCE,
              ITEM_TYPE_RESOURCE_GROUP,
              ITEM_TYPE_RESOURCE_SERVER,
              ITEM_TYPE_PROVIDER,
              ITEM_TYPE_COS,
              ITEM_TYPE_OWNER,
              ITEM_TYPE_AI_MODEL,
              ITEM_TYPE_DATA_BANK,
              ITEM_TYPE_APPS));
  public static final String VIEW = "View";
  public static final String DOWNLOAD = "Download";
  public static final String NGSILD = "NGSI-LD";
  public static final String GATEWAY = "GATEWAY";
}
