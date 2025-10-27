package org.cdpg.dx.rs.ngsild.queryparams;

import static org.cdpg.dx.apiserver.util.Util.toUriFunction;
import static org.cdpg.dx.rs.ngsild.util.NGSILDConstant.*;

import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonObject;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.rs.ngsild.searchmodels.GeoQuery;
import org.cdpg.dx.rs.ngsild.searchmodels.GeoRelation;
import org.cdpg.dx.rs.ngsild.searchmodels.TemporalQuery;
import org.cdpg.dx.rs.ngsild.temporal.model.TemporalGetRequest;

/** NGSILDQueryParams Class to parse query parameters from HTTP request. */
public class NGSILDQueryParams {
  private static final Logger LOGGER = LogManager.getLogger(NGSILDQueryParams.class);

  private List<URI> id;
  private String idPattern;
  private String type;
  private List<String> pick;
  private List<String> omit;
  private String textQuery;
  private TemporalQuery temporalQuery;
  private String options;
  private GeoRelation geoRel;
  private String geometry;
  private String coordinates;
  private String geoProperty;
  private String relation;
  private int pageFrom;
  private int pageSize;
  private boolean count;

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
    /*this.create(json);*/
  }

  public NGSILDQueryParams(TemporalGetRequest temporalGetRequest) {
    this.setTemporalQuery(new TemporalQuery());
    this.setGeoRel(new GeoRelation());

    if (temporalGetRequest == null) {
      return;
    }

    if (temporalGetRequest.getId() != null) {
      this.id = temporalGetRequest.getId();
    }

    if (temporalGetRequest.getPick() != null) {
      this.pick = temporalGetRequest.getPick();
    }
    if (temporalGetRequest.getOmit() != null) {
      this.omit = temporalGetRequest.getOmit();
    }
    if (temporalGetRequest.getQ() != null) {
      this.textQuery = temporalGetRequest.getQ();
    }
    if (temporalGetRequest.getOptions() != null) {
      this.options = temporalGetRequest.getOptions();
    }
    if (temporalGetRequest.getFrom() != null) {
      this.pageFrom = Integer.parseInt(temporalGetRequest.getFrom());
    }
    if (temporalGetRequest.getSize() != null) {
      this.pageSize = Integer.parseInt(temporalGetRequest.getSize());
    }

    TemporalQuery temporalQuery = temporalGetRequest.getTemporalQ();
    if (temporalQuery != null) {
      if (temporalQuery.getTimerel() != null) {
        this.temporalQuery.setTimerel(temporalQuery.getTimerel());
      }
      if (temporalQuery.getTimeAt() != null) {
        this.temporalQuery.setTimeAt(temporalQuery.getTimeAt());
      }
      if (temporalQuery.getEndtimeAt() != null) {
        this.temporalQuery.setEndtimeAt(temporalQuery.getEndtimeAt());
      }
      if (temporalQuery.getTimeproperty() != null) {
        this.temporalQuery.setTimeproperty(temporalQuery.getTimeproperty());
      }
    }
    GeoQuery geoQuery = temporalGetRequest.getGeoQ();
    if (geoQuery != null) {
      if (geoQuery.getGeometry() != null) {
        this.geometry = geoQuery.getGeometry();
      }
      if (geoQuery.getCoordinates() != null) {
        this.coordinates = geoQuery.getCoordinates().toString();
      }
      if (geoQuery.getGeoproperty() != null) {
        this.geoProperty = geoQuery.getGeoproperty();
      }
      GeoRelation geoRel = geoQuery.getGeorel();
      if (geoRel != null) {
        if (geoRel.getRelation() != null) {
          this.geoRel.setRelation(geoRel.getRelation());
        }
        if (geoRel.getMaxDistance() != 0) {
          this.geoRel.setMaxDistance(geoRel.getMaxDistance());
        }
        if (geoRel.getMinDistance() != 0) {
          this.geoRel.setMinDistance(geoRel.getMinDistance());
        }
      }
    }
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

  public String getTextQuery() {
    return textQuery;
  }

  public void setTextQuery(String textQuery) {
    this.textQuery = textQuery;
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
          this.temporalQuery.setTimeAt(entry.getValue());
          break;
        case NGSILDQUERY_ENDTIMEAT:
          this.temporalQuery.setEndtimeAt(entry.getValue());
          break;
        case NGSILDQUERY_TIMEPROPERTY:
          this.temporalQuery.setTimeproperty(entry.getValue());
          break;
        case NGSILDQUERY_Q:
          this.textQuery = entry.getValue();
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
        case NGSILDQUERY_SIZE:
          this.pageSize = Integer.parseInt(entry.getValue());
          break;
        case NGSILDQUERY_FROM:
          this.pageFrom = Integer.parseInt(entry.getValue());
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
    return textQuery;
  }

  public void setQ(String textQuery) {
    this.textQuery = textQuery;
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
        + ", textQuery='"
        + textQuery
        + '\''
        + ", temporalQuery="
        + temporalQuery.toJson()
        + ", options='"
        + options
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
        + '}';
  }
}
