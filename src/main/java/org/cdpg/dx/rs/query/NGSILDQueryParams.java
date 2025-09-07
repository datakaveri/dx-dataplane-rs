package org.cdpg.dx.rs.query;

import static org.cdpg.dx.apiserver.config.ApiConstants.*;
import static org.cdpg.dx.apiserver.util.Util.toUriFunction;

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

/** NGSILDQueryParams Class to parse query parameters from HTTP request. */
public class NGSILDQueryParams {
  private static final Logger LOGGER = LogManager.getLogger(NGSILDQueryParams.class);

  private List<URI> id;
  private List<String> type;
  private List<String> attrs;
  private List<String> idPattern;
  private String textQuery;
  private TemporalRelation temporalRelation;
  private String options;
  private GeoRelation geoRel;
  private String geometry;
  private String coordinates;
  private String geoProperty;
  private String relation;
  private String pageFrom;
  private String pageSize;

  public NGSILDQueryParams() {}

  /**
   * constructor a NGSILDParams passing query parameters map.
   *
   * @param paramsMap query paramater's map.
   */
  public NGSILDQueryParams(MultiMap paramsMap) {
    this.setTemporalRelation(new TemporalRelation());
    this.setGeoRel(new GeoRelation());
    this.create(paramsMap);
  }

  /**
   * constructor a NGSILDParams passing json.
   *
   * @param json JsonObject of query.
   */
  public NGSILDQueryParams(JsonObject json) {

    this.setTemporalRelation(new TemporalRelation());
    this.setGeoRel(new GeoRelation());
    this.create(json);
  }

  /**
   * constructor accepting typed QueryRequest to reduce reliance on raw JsonObject.
   *
   * @param request typed QueryRequest
   */
  public NGSILDQueryParams(QueryRequest request) {
    this.setTemporalRelation(new TemporalRelation());
    this.setGeoRel(new GeoRelation());

    if (request == null) {
      return;
    }

    if (request.getId() != null) {
      this.id = request.getId();
    }
    if (request.getType() != null) {
      this.type = request.getType();
    }
    if (request.getAttributes() != null) {
      this.attrs = request.getAttributes();
    }
    if (request.getIdPattern() != null) {
      this.idPattern = request.getIdPattern();
    }
    if (request.getQ() != null) {
      this.textQuery = request.getQ();
    }
    if (request.getOptions() != null) {
      this.options = request.getOptions();
    }
    if (request.getFrom() != null) {
      this.pageFrom = request.getFrom();
    }
    if (request.getSize() != null) {
      this.pageSize = request.getSize();
    }

    TemporalQuery tq = request.getTemporalQ();
    if (tq != null) {
      this.temporalRelation.setTimeRel(tq.getTimerel());
      this.temporalRelation.setTime(tq.getTime());
      this.temporalRelation.setEndTime(tq.getEndtime());
      // if TemporalRelation has a timeProperty field, set it here (not present in original flow)
      // this.temporalRelation.setTimeProperty(tq.getTimeProperty());
    }

    GeoQuery gq = request.getGeoQ();
    if (gq != null) {
      this.geometry = gq.getGeometry();
      if (gq.getCoordinates() != null) {
        // store as JSON array string to keep compatibility with existing code
        this.coordinates = gq.getCoordinates().toString();
      }
      this.geoProperty = gq.getGeoproperty();
      if (gq.getGeorel() != null) {
        this.geoRel.setRelation(gq.getGeorel().getRelation());
        this.geoRel.setMaxDistance(gq.getGeorel().getMaxDistance());
        this.geoRel.setMinDistance(gq.getGeorel().getMinDistance());
      }
    }
  }

  private void create(MultiMap paramsMap) {
    List<Entry<String, String>> entries = paramsMap.entries();
    for (final Entry<String, String> entry : entries) {
      switch (entry.getKey()) {
        case NGSILDQUERY_ID:
          this.id = new ArrayList<URI>();
          String[] ids = entry.getValue().split(",");
          List<URI> uris = Arrays.stream(ids).map(toUriFunction).collect(Collectors.toList());
          this.id.addAll(uris);
          break;
        case NGSILDQUERY_ATTRIBUTE:
          this.attrs = new ArrayList<String>();
          this.attrs.addAll(
              Arrays.stream(entry.getValue().split(",")).collect(Collectors.toList()));
          break;
        case NGSILDQUERY_TIMEREL:
          this.temporalRelation.setTimeRel(entry.getValue());
          break;
        case NGSILDQUERY_TIME:
          this.temporalRelation.setTime(entry.getValue());
          break;
        case NGSILDQUERY_ENDTIME:
          this.temporalRelation.setEndTime(entry.getValue());
          break;
        case NGSILDQUERY_Q:
          this.textQuery = entry.getValue();
          break;
        case IUDXQUERY_OPTIONS:
          this.options = entry.getValue();
          break;
        case NGSILDQUERY_SIZE:
          this.pageSize = entry.getValue();
          break;
        case NGSILDQUERY_FROM:
          this.pageFrom = entry.getValue();
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
    requestJson.forEach(
        entry -> {
          LOGGER.debug("key ::" + entry.getKey() + " value :: " + entry.getValue());
          if (entry.getKey().equalsIgnoreCase(NGSILDQUERY_Q)) {
            this.textQuery = requestJson.getString(NGSILDQUERY_Q);
          } else if (entry.getKey().equalsIgnoreCase(NGSILDQUERY_ATTRIBUTE)) {
            this.attrs = new ArrayList<String>();
            this.attrs =
                Arrays.stream(entry.getValue().toString().split(",")).collect(Collectors.toList());
          } else if (entry.getKey().equalsIgnoreCase(NGSILDQUERY_TYPE)) {
            this.type = new ArrayList<String>();
            this.type =
                Arrays.stream(entry.getValue().toString().split(",")).collect(Collectors.toList());
          } else if (entry.getKey().equalsIgnoreCase(NGSILDQUERY_TEMPORALQ)) {
            JsonObject temporalJson = requestJson.getJsonObject(entry.getKey());
            this.temporalRelation.setTimeRel(temporalJson.getString(NGSILDQUERY_TIMEREL));
            this.temporalRelation.setTime(temporalJson.getString(NGSILDQUERY_TIME));
            this.temporalRelation.setEndTime(temporalJson.getString(NGSILDQUERY_ENDTIME));
          } else if (entry.getKey().equalsIgnoreCase(JSON_ENTITIES)) {
            JsonArray array = new JsonArray(entry.getValue().toString());
            Iterator<?> iter = array.iterator();
            while (iter.hasNext()) {
              this.id = new ArrayList<URI>();
              this.idPattern = new ArrayList<String>();
              JsonObject entity = (JsonObject) iter.next();
              String id = entity.getString("id");
              String idPattern = entity.getString("idPattern");
              if (id != null) {
                this.id.add(toUri(id));
              }
              if (idPattern != null) {
                this.idPattern.add(idPattern);
              }
            }
          } else if (entry.getKey().equalsIgnoreCase(IUDXQUERY_OPTIONS)) {
            this.options = requestJson.getString(entry.getKey());
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
          } else if (entry.getKey().equalsIgnoreCase(NGSILDQUERY_FROM)) {
            this.pageFrom = requestJson.getString(entry.getKey());
          } else if (entry.getKey().equalsIgnoreCase(NGSILDQUERY_SIZE)) {
            this.pageSize = requestJson.getString(NGSILDQUERY_SIZE);
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

  public List<String> getType() {
    return type;
  }

  public void setType(List<String> type) {
    this.type = type;
  }

  public List<String> getAttrs() {
    return attrs;
  }

  public void setAttrs(List<String> attrs) {
    this.attrs = attrs;
  }

  public List<String> getIdPattern() {
    return idPattern;
  }

  public void setIdPattern(List<String> idPattern) {
    this.idPattern = idPattern;
  }

  public String getQ() {
    return textQuery;
  }

  public void setQ(String textQuery) {
    this.textQuery = textQuery;
  }

  public TemporalRelation getTemporalRelation() {
    return temporalRelation;
  }

  public void setTemporalRelation(TemporalRelation temporalRelation) {
    this.temporalRelation = temporalRelation;
  }

  public String getOptions() {
    return options;
  }

  public void setOptions(String options) {
    this.options = options;
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

  public String getPageFrom() {
    return pageFrom;
  }

  public String getPageSize() {
    return pageSize;
  }

  @Override
  public String toString() {
    return "NGSILDQueryParams [id="
        + id
        + ", type="
        + type
        + ", attrs="
        + attrs
        + ", idPattern="
        + idPattern
        + ", textQuery="
        + textQuery
        + ", geoRel="
        + geoRel
        + ", geometry="
        + geometry
        + ", coordinates="
        + coordinates
        + ", geoProperty="
        + geoProperty
        + ", temporalRelation="
        + temporalRelation
        + ", options="
        + options
        + "]";
  }
}
