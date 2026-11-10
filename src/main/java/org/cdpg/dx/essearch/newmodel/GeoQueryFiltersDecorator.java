package org.cdpg.dx.essearch.newmodel;

import static org.cdpg.dx.database.elastic.util.Constants.*;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.cdpg.dx.common.exception.DxBadRequestException;
import org.cdpg.dx.database.elastic.model.QueryModel;
import org.cdpg.dx.database.elastic.util.QueryType;
import org.cdpg.dx.essearch.model.ElasticsearchQueryDecorator;
import org.cdpg.dx.essearch.model.FilterType;

import org.cdpg.dx.rs.ngsild.searchmodels.GeoQ;
import org.locationtech.jts.geom.Geometry;
import org.wololo.jts2geojson.GeoJSONReader;

public class GeoQueryFiltersDecorator implements ElasticsearchQueryDecorator {
  private Map<FilterType, List<QueryModel>> queryFilters;
  private GeoQ geoQ;

  public GeoQueryFiltersDecorator(
          Map<FilterType, List<QueryModel>> queryFilters, GeoQ geoQ) {
    this.queryFilters = queryFilters;
    this.geoQ = geoQ;
  }

  @Override
  public Map<FilterType, List<QueryModel>> add() {
    if (geoQ == null) {
      throw new DxBadRequestException("Missing geoQ parameters");
    }
    
    String geometry = geoQ.getGeometry();
    String relation = geoQ.getGeoRel();
    String geoProperty = geoQ.getGeoproperty();
    
    // Validate required fields as in old code
    if (geometry == null || relation == null || geoProperty == null) {
      throw new DxBadRequestException("Missing/Invalid geo parameters");
    }
    
    // Handle different geometry types as in old code
    if ("point".equalsIgnoreCase(geometry)) {
      // Point with radius (circle) - check for coordinates and maxDistance
      List<Double> coords = geoQ.getPointCoordinates();
      Double maxDistance = geoQ.getMaxDistance();
      
      if (coords == null || coords.size() != 2 || maxDistance == null) {
        throw new DxBadRequestException("Point geometry requires coordinates and maxDistance for near queries");
      }
      if(maxDistance>1000){
        throw new DxBadRequestException("maxDistance should not be greater than 1000 meters");
      }
      
      // Validate relation for point
      if (!relation.startsWith("near")) {
        throw new DxBadRequestException("Point geometry only supports 'near' relation");
      }
      
      // Validate coordinates with Point geometry (JTS compatible)
      JsonObject pointGeoJson = new JsonObject();
      pointGeoJson.put("type", "Point");
      pointGeoJson.put("coordinates", new JsonArray().add(coords.get(0)).add(coords.get(1)));
      validateGeometry(pointGeoJson);
      
      // Build geo_distance query for Point with near relation
      buildAndAddDistanceQuery(geoProperty, coords, maxDistance);
      
    } else if ("polygon".equalsIgnoreCase(geometry) || "linestring".equalsIgnoreCase(geometry)) {
      // Polygon & LineString - check for coordinates
      List<List<Double>> coords = null;
      if ("polygon".equalsIgnoreCase(geometry)) {
        coords = geoQ.getPolygonCoordinates();
      } else {
        coords = geoQ.getLinestringCoordinates();
      }
      
      if (coords == null || coords.isEmpty()) {
        throw new DxBadRequestException("Missing coordinates for " + geometry);
      }
      
      // Validate relation for polygon/linestring
      if (!("within".equalsIgnoreCase(relation) || "intersects".equalsIgnoreCase(relation))) {
        throw new DxBadRequestException(geometry + " supports only 'within' or 'intersects' relations");
      }
      
      // Validate coordinates as in old code
      if ("polygon".equalsIgnoreCase(geometry)) {
        validatePolygonCoordinates(coords);
      } else if ("linestring".equalsIgnoreCase(geometry)) {
        validateLineStringCoordinates(coords);
      }
      
      // Build geometry
      JsonObject geoJson = buildGeometryJson(geometry, coords);
      
      // Validate with JTS
      validateGeometry(geoJson);
      
      // Build query
      buildAndAddQuery(geoProperty, geoJson, relation);
      
    } else if ("bbox".equalsIgnoreCase(geometry)) {
      // BBox - check for coordinates
      List<List<Double>> coords = geoQ.getBboxCoordinates();
      
      if (coords == null || coords.size() != 2) {
        throw new DxBadRequestException("BBox requires exactly 2 coordinate pairs");
      }
      
      // Validate relation for bbox
      if (!("within".equalsIgnoreCase(relation) || "intersects".equalsIgnoreCase(relation))) {
        throw new DxBadRequestException("BBox supports only 'within' or 'intersects' relations");
      }
      
      // Validate coordinates with LineString geometry (JTS compatible, as in old code)
      JsonObject lineStringGeoJson = new JsonObject();
      lineStringGeoJson.put("type", "LineString");
      JsonArray lineCoords = new JsonArray();
      for (List<Double> pair : coords) {
        lineCoords.add(new JsonArray().add(pair.get(0)).add(pair.get(1)));
      }
      lineStringGeoJson.put("coordinates", lineCoords);
      validateGeometry(lineStringGeoJson);
      
      // Build envelope geometry for Elasticsearch query
      JsonObject geoJson = new JsonObject();
      geoJson.put("type", "envelope");
      geoJson.put("coordinates", lineCoords);
      
      // Build query
      buildAndAddQuery(geoProperty, geoJson, relation);
      
    } else {
      throw new DxBadRequestException("Unsupported geometry type: " + geometry);
    }
    
    return queryFilters;
  }

  private void validatePolygonCoordinates(List<List<Double>> coords) {
    if (coords.size() < 4 || coords.size() > 10) {
      throw new DxBadRequestException("Polygon requires 4-10 coordinates");
    }
    
    // Check if first and last coordinates match (closed polygon)
    List<Double> first = coords.get(0);
    List<Double> last = coords.get(coords.size() - 1);
    if (!first.equals(last)) {
      throw new DxBadRequestException("Coordinate mismatch (Polygon)");
    }
  }

  private void validateLineStringCoordinates(List<List<Double>> coords) {
    if (coords.size() < 2 || coords.size() > 10) {
      throw new DxBadRequestException("LineString requires 2-10 coordinates");
    }
  }
  
  private JsonObject buildGeometryJson(String geometry, List<List<Double>> coords) {
    JsonObject geoJson = new JsonObject();
    
    switch (geometry.toLowerCase()) {
      case "polygon":
        JsonArray ring = new JsonArray();
        for (List<Double> pair : coords) {
          ring.add(new JsonArray().add(pair.get(0)).add(pair.get(1)));
        }
        geoJson.put("type", "Polygon");
        geoJson.put("coordinates", new JsonArray().add(ring));
        break;
        
      case "linestring":
        JsonArray lineCoords = new JsonArray();
        for (List<Double> pair : coords) {
          // Swap coordinates: input is [lat, lon], but GeoJSON expects [lon, lat]
          lineCoords.add(new JsonArray().add(pair.get(0)).add(pair.get(1)));
        }
        geoJson.put("type", "LineString");
        geoJson.put("coordinates", lineCoords);
        break;
        
      case "bbox":
        JsonArray bboxCoords = new JsonArray();
        for (List<Double> pair : coords) {
          // Swap coordinates: input is [lat, lon], but GeoJSON expects [lon, lat]
          bboxCoords.add(new JsonArray().add(pair.get(1)).add(pair.get(0)));
        }
        geoJson.put("type", "envelope");
        geoJson.put("coordinates", bboxCoords);
        break;
        
      default:
        throw new DxBadRequestException("Unsupported geometry type: " + geometry);
    }
    
    return geoJson;
  }
  
  private void validateGeometry(JsonObject geoJson) {
    try {
      GeoJSONReader reader = new GeoJSONReader();
      Geometry jtsGeom = reader.read(geoJson.toString());
      if (!jtsGeom.isValid()) {
        throw new DxBadRequestException("Invalid geometry");
      }
    } catch (Exception e) {
      throw new DxBadRequestException("Invalid geometry: " + e.getMessage());
    }
  }
  
  private void buildAndAddQuery(String geoProperty, JsonObject geoJson, String relation) {
    // Build QueryModel with proper parameters as in old code
    QueryModel geoWrapperQuery = new QueryModel(QueryType.GEO_SHAPE);
    
    // Create mutable map for parameters
    Map<String, Object> params = new HashMap<>();
    params.put(TYPE, geoJson.getString("type"));
    params.put(COORDINATES, geoJson.getJsonArray("coordinates"));
    params.put("relation", relation);
    params.put(GEO_PROPERTY, geoProperty);
    
    // Add radius parameter for Circle geometry
    if ("Circle".equals(geoJson.getString("type"))) {
      params.put("radius", geoJson.getString("radius"));
    }
    
    geoWrapperQuery.setQueryParameters(params);
    
    List<QueryModel> queryList = queryFilters.get(FilterType.FILTER);
    queryList.add(geoWrapperQuery);
  }
  
  private void buildAndAddDistanceQuery(String geoProperty, List<Double> coords, Double maxDistance) {
    QueryModel geoDistanceQuery = new QueryModel(QueryType.GEO_DISTANCE);
    
    Map<String, Object> params = new HashMap<>();
    params.put(GEO_PROPERTY, geoProperty);
    // Swap coordinates: input is [lat, lon], but GeoJSON expects [lon, lat]
    params.put(COORDINATES, new JsonArray().add(coords.get(1)).add(coords.get(0)));
    params.put("distance", maxDistance + "m");
    
    geoDistanceQuery.setQueryParameters(params);
    
    List<QueryModel> queryList = queryFilters.get(FilterType.FILTER);
    queryList.add(geoDistanceQuery);
  }
}