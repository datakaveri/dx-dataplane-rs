package org.cdpg.dx.rs.ngsild.queryparams;

import static org.cdpg.dx.apiserver.util.Util.toUriFunction;
import static org.cdpg.dx.rs.ngsild.util.NGSILDConstant.*;

import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.rs.ngsild.searchmodels.GeoRelation;
import org.cdpg.dx.rs.ngsild.searchmodels.TemporalQuery;
import org.cdpg.dx.rs.ngsild.util.NGSILDConstant;

/** NGSILDQueryParams Class to parse query parameters from HTTP request. */
public class NGSILDQueryParams {
  private static final Logger LOGGER = LogManager.getLogger(NGSILDQueryParams.class);

  private List<URI> id;
  private String idPattern;
  private String type;
  private List<String> pick;
  private List<String> omit;
  private String q;
  private List<String> attrs;
  private TemporalQuery temporalQuery;
  private String options;
  private String format;
  private GeoRelation geoRel;
  private String geometry;
  private String coordinates;
  private String geoProperty;
  private String relation;
  private int pageFrom = 0;
  private int pageSize = 100;
  private boolean count;
  private int lastN;
  // Aggregation parameters (NGSI-LD extensions)
  private List<String> aggrMethods;
  private String aggrPeriodDuration;

  public NGSILDQueryParams() {}

  /**
   * constructor a NGSILDParams passing query parameters map.
   *
   * @param paramsMap query paramater's map.
   */
  public NGSILDQueryParams(MultiMap paramsMap) {
    this.setTemporalQuery(new TemporalQuery());
    this.setGeoRel(new GeoRelation());
    this.create(paramsMap);
  }

  /**
   * constructor a NGSILDParams passing json.
   *
   * @param json JsonObject of query.
   */
  public NGSILDQueryParams(JsonObject json) {

    this.setTemporalQuery(new TemporalQuery());
    this.setGeoRel(new GeoRelation());
    this.create(json);
  }

  public String getIdPattern() {
    return idPattern;
  }

  public void setIdPattern(String idPattern) {
    this.idPattern = idPattern;
  }

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public List<String> getPick() {
    return pick;
  }

  public void setPick(List<String> pick) {
    this.pick = pick;
  }

  public List<String> getOmit() {
    return omit;
  }

  public void setOmit(List<String> omit) {
    this.omit = omit;
  }

  public boolean isCount() {
    return count;
  }

  public void setCount(boolean count) {
    this.count = count;
  }

  private void create(MultiMap paramsMap) {
    List<Entry<String, String>> entries = paramsMap.entries();
    for (final Entry<String, String> entry : entries) {
      LOGGER.warn(entry.getKey() + " : " + entry.getValue());
      switch (entry.getKey()) {
        case NGSILDQUERY_ID:
          this.id = new ArrayList<URI>();
          String[] ids = entry.getValue().split(",");
          List<URI> uris = Arrays.stream(ids).map(toUriFunction).collect(Collectors.toList());
          this.id.addAll(uris);
          break;
        case NGSILDQUERY_PICK:
          this.pick = new ArrayList<String>();
          this.pick.addAll(Arrays.stream(entry.getValue().split(",")).collect(Collectors.toList()));
          break;
        case NGSILDQUERY_OMIT:
          this.omit = new ArrayList<String>();
          this.omit.addAll(Arrays.stream(entry.getValue().split(",")).collect(Collectors.toList()));
          break;
        case NGSILDQUERY_TIMEREL:
          this.temporalQuery.setTimerel(entry.getValue());
          break;
        case NGSILDQUERY_TIMEAT:
          String timeAtNormalized = entry.getValue().trim().replaceAll("\\s", "+");
          this.temporalQuery.setTimeAt(timeAtNormalized);
          break;
        case NGSILDQUERY_ENDTIMEAT:
          String endTimeAtNormalized = entry.getValue().trim().replaceAll("\\s", "+");
          this.temporalQuery.setEndtimeAt(endTimeAtNormalized);
          break;
        case NGSILDQUERY_TIMEPROPERTY:
          this.temporalQuery.setTimeproperty(entry.getValue());
          break;
        case NGSILDQUERY_Q:
          this.q = entry.getValue();
          break;
        case NGSILDQUERY_ATTRIBUTE:
          this.attrs =
              Arrays.stream(entry.getValue().split(","))
                  .map(String::trim)
                  .collect(Collectors.toList());
          break;
        case NGSILDQUERY_TYPE:
          this.type = entry.getValue();
          break;
        case NGSILDQUERY_IDPATTERN:
          this.idPattern = entry.getValue();
          break;
        case NGSILD_OPTIONS:
          this.options = entry.getValue();
          break;
        case NGSILD_FORMAT:
          this.format = entry.getValue();
          break;
        case NGSILDQUERY_SIZE:
          this.pageSize = Integer.parseInt(entry.getValue());
          break;
        case NGSILDQUERY_AGGR_METHODS:
          this.aggrMethods =
              Arrays.stream(entry.getValue().split(",")).collect(Collectors.toList());
          break;
        /*case NGSILDQUERY_AGGR_PERIOD_DURATION:
          this.aggrPeriodDuration = entry.getValue();
          break;*/
        case NGSILDQUERY_FROM:
          this.pageFrom = Integer.parseInt(entry.getValue());
          break;
        case NGSILDQUERY_LASTN:
          this.lastN = Integer.parseInt(entry.getValue());
          break;
        case NGSILDQUERY_COUNT:
          this.count = Boolean.parseBoolean(entry.getValue());
          break;
        case NGSILDQUERY_GEOREL:
          String georel = entry.getValue();
          String[] values = georel.split(";");
          this.geoRel.setRelation(values[0]);
          if (values.length == 2) {
            String[] distance = values[1].split("=");
            if (distance[0].equalsIgnoreCase(NGSILDQUERY_MAXDISTANCE)) {
              this.geoRel.setMaxDistance(Double.parseDouble(distance[1]));
            } else if (distance[0].equalsIgnoreCase(NGSILDQUERY_MINDISTANCE)) {
              this.geoRel.setMinDistance(Double.parseDouble(distance[1]));
            }
          }
          break;
        case NGSILDQUERY_GEOMETRY:
          this.geometry = entry.getValue();
          break;
        case NGSILDQUERY_COORDINATES:
          this.coordinates = entry.getValue();
          break;
        case NGSILDQUERY_GEOPROPERTY:
          this.geoProperty = entry.getValue();
          break;
        default:
          LOGGER.warn(MSG_INVALID_PARAM + ":" + entry.getKey());
          break;
      }
    }
  }

  private void create(JsonObject requestJson) {
    LOGGER.info("create from json started");
    requestJson.forEach(
        entry -> {
          LOGGER.debug("key ::" + entry.getKey() + " value :: " + entry.getValue());
          if (entry.getKey().equalsIgnoreCase(NGSILDQUERY_Q)) {
            this.q = requestJson.getString(NGSILDQUERY_Q);
          } else if (entry.getKey().equalsIgnoreCase(NGSILDQUERY_PICK)) {
            this.pick = new ArrayList<String>();
            this.pick =
                Arrays.stream(entry.getValue().toString().split(",")).collect(Collectors.toList());
          } else if (entry.getKey().equalsIgnoreCase(NGSILDQUERY_OMIT)) {
            this.omit = new ArrayList<String>();
            this.omit =
                Arrays.stream(entry.getValue().toString().split(",")).collect(Collectors.toList());
          } else if (entry.getKey().equalsIgnoreCase("geoQ")) {
            JsonObject geoJson = requestJson.getJsonObject(entry.getKey());
            this.setGeometry(geoJson.getString("geometry"));
            this.setGeoProperty(geoJson.getString("geoproperty"));
            this.setCoordinates(geoJson.getJsonArray("coordinates").toString());
            if (geoJson.containsKey("georel")) {
              String georel = geoJson.getString("georel");
              String[] values = georel.split(";");
              this.geoRel.setRelation(values[0]);
              if (values.length == 2) {
                String[] distance = values[1].split("=");
                if (distance[0].equalsIgnoreCase(NGSILDQUERY_MAXDISTANCE)) {
                  this.geoRel.setMaxDistance(Double.parseDouble(distance[1]));
                } else if (distance[0].equalsIgnoreCase(NGSILDQUERY_MINDISTANCE)) {
                  this.geoRel.setMinDistance(Double.parseDouble(distance[1]));
                }
              }
            }
          } else if (entry.getKey().equalsIgnoreCase("temporalQ")) {
            JsonObject temporalJson = requestJson.getJsonObject(entry.getKey());
            this.temporalQuery.setTimerel(temporalJson.getString("timerel"));
            this.temporalQuery.setTimeAt(temporalJson.getString("timeAt"));
            this.temporalQuery.setEndtimeAt(temporalJson.getString("endtimeAt"));
            this.temporalQuery.setTimeproperty(
                temporalJson.getString("timeproperty", "observationDateTime"));
          } else if (entry.getKey().equalsIgnoreCase("entities")) {
            JsonArray array = new JsonArray(entry.getValue().toString());
            Iterator<?> iter = array.iterator();
            while (iter.hasNext()) {
              this.id = new ArrayList<URI>();
              /*this.idPattern = new ArrayList<String>();*/
              JsonObject entity = (JsonObject) iter.next();
              String id = entity.getString("id");
              String idPattern = entity.getString("idPattern");
              String type = entity.getString("type");
              if (id != null) {
                this.id.add(toUri(id));
              }
              if (idPattern != null) {
                this.idPattern = idPattern;
              }
              if (type != null) {
                this.type = type;
              }
            }
          } else if (entry.getKey().equalsIgnoreCase(NGSILD_OPTIONS)) {
            this.options = requestJson.getString(entry.getKey());
          } else if (entry.getKey().equalsIgnoreCase(NGSILD_FORMAT)) {
            this.format = requestJson.getString(entry.getKey());
          } else if (entry.getKey().equalsIgnoreCase(NGSILDQUERY_COUNT)) {
            this.count = Boolean.parseBoolean(requestJson.getString(entry.getKey()));
          } else if (entry.getKey().equalsIgnoreCase(NGSILDQUERY_FROM)) {
            this.pageFrom = Integer.parseInt(requestJson.getString(entry.getKey()));
          } else if (entry.getKey().equalsIgnoreCase(NGSILDQUERY_SIZE)) {
            this.pageSize =
                Integer.parseInt(requestJson.getString(NGSILDConstant.NGSILDQUERY_SIZE));
          }
        });
  }

  private URI toUri(String source) {
    URI uri = null;
    try {
      uri = new URI(source);
    } catch (URISyntaxException e) {
      e.printStackTrace();
    }
    return uri;
  }

  public List<URI> getId() {
    return id;
  }

  public void setId(List<URI> id) {
    this.id = id;
  }

  public String getQ() {
    return q;
  }

  public void setQ(String textQuery) {
    this.q = textQuery;
  }

  public TemporalQuery getTemporalQuery() {
    return temporalQuery;
  }

  public void setTemporalQuery(TemporalQuery temporalQuery) {
    this.temporalQuery = temporalQuery;
  }

  public String getOptions() {
    return options;
  }

  public void setOptions(String options) {
    this.options = options;
  }

  public String getFormat() {
    return format;
  }

  public List<String> getAttrs() {
    return attrs;
  }

  public GeoRelation getGeoRel() {
    return geoRel;
  }

  public void setGeoRel(GeoRelation geoRel) {
    this.geoRel = geoRel;
  }

  public String getGeometry() {
    return geometry;
  }

  public void setGeometry(String geometry) {
    this.geometry = geometry;
  }

  public String getCoordinates() {
    return coordinates;
  }

  public void setCoordinates(String coordinates) {
    this.coordinates = coordinates;
  }

  public String getGeoProperty() {
    return geoProperty;
  }

  public void setGeoProperty(String geoProperty) {
    this.geoProperty = geoProperty;
  }

  public String getRelation() {
    return relation;
  }

  public void setRelation(String relation) {
    this.relation = relation;
  }

  public int getPageFrom() {
    return pageFrom;
  }

  public void setPageFrom(int pageFrom) {
    this.pageFrom = pageFrom;
  }

  public int getPageSize() {
    return pageSize;
  }

  public void setPageSize(int pageSize) {
    this.pageSize = pageSize;
  }

  @Override
  public String toString() {
    return "NGSILDQueryParams{"
        + "id="
        + id
        + ", idPattern='"
        + idPattern
        + '\''
        + ", type='"
        + type
        + '\''
        + ", pick="
        + pick
        + ", omit="
        + omit
        + ", q='"
        + q
        + '\''
        + ", attrs="
        + attrs
        + ", temporalQuery="
        + temporalQuery.toJson()
        + ", options='"
        + options
        + '\''
        + ", format='"
        + format
        + '\''
        + ", geoRel="
        + geoRel.toString()
        + ", geometry='"
        + geometry
        + '\''
        + ", coordinates='"
        + coordinates
        + '\''
        + ", geoProperty='"
        + geoProperty
        + '\''
        + ", relation='"
        + relation
        + '\''
        + ", pageFrom="
        + pageFrom
        + ", pageSize="
        + pageSize
        + ", count="
        + count
        + ", lastN="
        + lastN
        + ", aggrMethods="
        + aggrMethods
        + '\''
        + '}';
  }

  public int getLastN() {
    return lastN;
  }

  public void setLastN(int lastN) {
    this.lastN = lastN;
  }

  public List<String> getAggrMethods() {
    return aggrMethods;
  }

  public void setAggrMethods(List<String> aggrMethods) {
    this.aggrMethods = aggrMethods;
  }

  public String getAggrPeriodDuration() {
    return aggrPeriodDuration;
  }

  public void setAggrPeriodDuration(String aggrPeriodDuration) {
    this.aggrPeriodDuration = aggrPeriodDuration;
  }
}
