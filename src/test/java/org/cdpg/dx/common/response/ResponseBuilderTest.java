package org.cdpg.dx.common.response;

import static org.junit.jupiter.api.Assertions.*;

import org.cdpg.dx.common.HttpStatusCode;
import org.cdpg.dx.common.URNGenerator;
import org.cdpg.dx.common.util.PaginationInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ResponseBuilder Tests")
class ResponseBuilderTest {

  private URNGenerator urnGenerator;

  @BeforeEach
  void setUp() {
    urnGenerator = new URNGenerator("urn:dx:rs:");
  }

  @Nested
  @DisplayName("success() method Tests")
  class SuccessMethodTests {

    @Test
    @DisplayName("success with detail and result should return DxResponse with correct fields")
    void successWithDetailAndResultShouldReturnCorrectResponse() {
      DxResponse<String> response =
          ResponseBuilder.success(urnGenerator, "Operation completed", "resultData");

      assertEquals("urn:dx:rs:success", response.getType());
      assertEquals("Success", response.getTitle());
      assertEquals("Operation completed", response.getDetail());
      assertEquals("resultData", response.getResult());
      assertNull(response.getPaginationInfo());
    }

    @Test
    @DisplayName("success with detail, result, and pagination should include pagination info")
    void successWithPaginationShouldIncludePaginationInfo() {
      PaginationInfo pageInfo = new PaginationInfo(1, 10, 100, 10, true, false);
      DxResponse<String> response =
          ResponseBuilder.success(urnGenerator, "Fetched data", "data", pageInfo);

      assertEquals("urn:dx:rs:success", response.getType());
      assertEquals("Fetched data", response.getDetail());
      assertEquals("data", response.getResult());
      assertNotNull(response.getPaginationInfo());
      assertEquals(1, response.getPaginationInfo().getPage());
      assertEquals(10, response.getPaginationInfo().getSize());
      assertEquals(100, response.getPaginationInfo().getTotalCount());
    }

    @Test
    @DisplayName("success with detail only should return response with null result")
    void successWithDetailOnlyShouldReturnNullResult() {
      DxResponse<Void> response = ResponseBuilder.success(urnGenerator, "Done");

      assertEquals("urn:dx:rs:success", response.getType());
      assertEquals("Done", response.getDetail());
      assertNull(response.getResult());
    }

    @Test
    @DisplayName("success should generate URN using urnGenerator")
    void successShouldGenerateUrnCorrectly() {
      URNGenerator customUrn = new URNGenerator("urn:dx:acl:");
      DxResponse<Void> response = ResponseBuilder.success(customUrn, "test");
      assertEquals("urn:dx:acl:success", response.getType());
    }
  }

  @Nested
  @DisplayName("DxResponse direct construction Tests")
  class DxResponseTests {

    @Test
    @DisplayName("DxResponse should store all fields correctly")
    void dxResponseShouldStoreAllFields() {
      PaginationInfo pageInfo = new PaginationInfo(2, 20, 200, 10, true, true);
      DxResponse<String> response =
          new DxResponse<>("urn:test", "Test Title", "Test detail", "result", pageInfo);

      assertEquals("urn:test", response.getType());
      assertEquals("Test Title", response.getTitle());
      assertEquals("Test detail", response.getDetail());
      assertEquals("result", response.getResult());
      assertEquals(2, response.getPaginationInfo().getPage());
    }

    @Test
    @DisplayName("DxResponse setters should modify fields")
    void dxResponseSettersShouldModifyFields() {
      DxResponse<String> response = new DxResponse<>();
      response.setType("urn:type");
      response.setTitle("Title");
      response.setDetail("Detail");
      response.setResult("data");

      assertEquals("urn:type", response.getType());
      assertEquals("Title", response.getTitle());
      assertEquals("Detail", response.getDetail());
      assertEquals("data", response.getResult());
    }
  }
}
