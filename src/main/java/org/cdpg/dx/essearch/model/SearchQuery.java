package org.cdpg.dx.essearch.model;

import java.util.List;
import org.cdpg.dx.rs.latest.util.dtoUtil.GeoQ;

public class SearchQuery {
  private String searchType;
  private Integer size;
  private Integer page;
  private String id;
  private List<String> filter;
  private TextSearchRequest textSearchRequest;
  private SearchCriteriaRequest searchCriteriaRequest;
  private AccessPolicyRequest accessPolicyRequest;
  private InstanceFilterRequest instanceFilterRequest;
  private ResponseFilterRequest responseFilterRequest;
  private List<OrderBy> sort;
  private GeoQ geoQ;

  public SearchQuery(
      String searchType,
      Integer size,
      Integer page,
      String id,
      List<String> filter,
      TextSearchRequest textSearchRequest,
      SearchCriteriaRequest searchCriteriaRequest,
      AccessPolicyRequest accessPolicyRequest,
      InstanceFilterRequest instanceFilterRequest,
      ResponseFilterRequest responseFilterRequest,
      List<OrderBy> sort,
      GeoQ geoQ) {
    this.searchType = searchType;
    this.size = size;
    this.page = page;
    this.id = id;
    this.filter = filter;
    this.textSearchRequest = textSearchRequest;
    this.searchCriteriaRequest = searchCriteriaRequest;
    this.accessPolicyRequest = accessPolicyRequest;
    this.instanceFilterRequest = instanceFilterRequest;
    this.responseFilterRequest = responseFilterRequest;
    this.sort = sort;
    this.geoQ = geoQ;
  }

  public List<OrderBy> getSort() {
    return sort;
  }

  public void setSort(List<OrderBy> sort) {
    this.sort = sort;
  }

  public Integer getSize() {
    return size;
  }

  public void setSize(Integer size) {
    this.size = size;
  }

  public Integer getPage() {
    return page;
  }

  public void setPage(Integer page) {
    this.page = page;
  }

  public String getSearchType() {
    return searchType;
  }

  public void setSearchType(String searchType) {
    this.searchType = searchType;
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public List<String> getFilter() {
    return filter;
  }

  public void setFilter(List<String> filter) {
    this.filter = filter;
  }

  public TextSearchRequest getTextSearchRequest() {
    return textSearchRequest;
  }

  public void setTextSearchRequest(TextSearchRequest textSearchRequest) {
    this.textSearchRequest = textSearchRequest;
  }

  public SearchCriteriaRequest getSearchCriteriaRequest() {
    return searchCriteriaRequest;
  }

  public void setSearchCriteriaRequest(SearchCriteriaRequest searchCriteriaRequest) {
    this.searchCriteriaRequest = searchCriteriaRequest;
  }

  public AccessPolicyRequest getAccessPolicyRequest() {
    return accessPolicyRequest;
  }

  public void setAccessPolicyRequest(AccessPolicyRequest accessPolicyRequest) {
    this.accessPolicyRequest = accessPolicyRequest;
  }

  public InstanceFilterRequest getInstanceFilterRequest() {
    return instanceFilterRequest;
  }

  public void setInstanceFilterRequest(InstanceFilterRequest instanceFilterRequest) {
    this.instanceFilterRequest = instanceFilterRequest;
  }

  public ResponseFilterRequest getResponseFilterRequest() {
    return responseFilterRequest;
  }

  public void setResponseFilterRequest(ResponseFilterRequest responseFilterRequest) {
    this.responseFilterRequest = responseFilterRequest;
  }

  public GeoQ getGeoQ() {
    return geoQ;
  }

  public void setGeoQ(GeoQ geoQ) {
    this.geoQ = geoQ;
  }
}
