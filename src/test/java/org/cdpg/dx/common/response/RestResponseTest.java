package org.cdpg.dx.common.response;

import static org.junit.jupiter.api.Assertions.*;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("RestResponse Tests")
class RestResponseTest {

  @Nested
  @DisplayName("Builder Pattern Tests")
  class BuilderPatternTests {

    @Test
    @DisplayName("should build RestResponse with type, title, and message")
    void shouldBuildRestResponseWithAllFields() {
      RestResponse response =
          new RestResponse.Builder()
              .withType("urn:dx:acl:success")
              .withTitle("Success")
              .withMessage("Operation completed successfully")
              .build();

      JsonObject json = response.toJson();
      assertEquals("urn:dx:acl:success", json.getString("type"));
      assertEquals("Success", json.getString("title"));
      assertEquals("Operation completed successfully", json.getString("detail"));
      assertFalse(json.containsKey("statusCode"));
    }

    @Test
    @DisplayName("should build RestResponse with null fields")
    void shouldBuildWithNullFields() {
      RestResponse response = new RestResponse.Builder().build();

      JsonObject json = response.toJson();
      assertNull(json.getString("type"));
      assertNull(json.getString("title"));
      assertNull(json.getString("detail"));
    }

    @Test
    @DisplayName("builder should support fluent chaining")
    void builderShouldSupportFluentChaining() {
      RestResponse.Builder builder = new RestResponse.Builder();
      RestResponse.Builder returned = builder.withType("type").withTitle("title").withMessage("msg");
      assertSame(builder, returned);
    }
  }

  @Nested
  @DisplayName("toJson Tests")
  class ToJsonTests {

    @Test
    @DisplayName("toJson without status code should not include statusCode field")
    void toJsonWithoutStatusCodeShouldNotIncludeStatusCode() {
      RestResponse response =
          new RestResponse.Builder()
              .withType("urn:test")
              .withTitle("Test")
              .withMessage("test detail")
              .build();

      JsonObject json = response.toJson();
      assertEquals(3, json.size());
      assertFalse(json.containsKey("statusCode"));
    }

    @Test
    @DisplayName("toJson with status code should include statusCode field")
    void toJsonWithStatusCodeShouldIncludeStatusCode() {
      RestResponse response =
          new RestResponse.Builder()
              .build(200, "urn:dx:acl:success", "Success", "All good");

      JsonObject json = response.toJson();
      assertEquals(200, json.getInteger("statusCode"));
      assertEquals("urn:dx:acl:success", json.getString("type"));
      assertEquals("Success", json.getString("title"));
      assertEquals("All good", json.getString("detail"));
      assertEquals(4, json.size());
    }

    @Test
    @DisplayName("build(statusCode, ...) should override previous builder fields")
    void buildWithStatusCodeShouldOverridePreviousFields() {
      RestResponse response =
          new RestResponse.Builder()
              .withType("old-type")
              .withTitle("old-title")
              .withMessage("old-msg")
              .build(404, "new-type", "Not Found", "Resource not found");

      JsonObject json = response.toJson();
      assertEquals(404, json.getInteger("statusCode"));
      assertEquals("new-type", json.getString("type"));
      assertEquals("Not Found", json.getString("title"));
      assertEquals("Resource not found", json.getString("detail"));
    }
  }

  @Nested
  @DisplayName("Edge Cases")
  class EdgeCaseTests {

    @Test
    @DisplayName("toJson with status 0 should not include statusCode")
    void toJsonWithStatus0ShouldNotIncludeStatusCode() {
      RestResponse response =
          new RestResponse.Builder()
              .withType("type")
              .withTitle("title")
              .withMessage("detail")
              .build();

      JsonObject json = response.toJson();
      assertFalse(json.containsKey("statusCode"));
    }
  }
}
