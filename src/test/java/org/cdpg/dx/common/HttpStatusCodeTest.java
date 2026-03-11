package org.cdpg.dx.common;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("HttpStatusCode Tests")
class HttpStatusCodeTest {

  @Nested
  @DisplayName("Value and Description Tests")
  class ValueAndDescriptionTests {

    @Test
    @DisplayName("SUCCESS should have value 200 and correct description")
    void successShouldHaveCorrectValues() {
      assertEquals(200, HttpStatusCode.SUCCESS.getValue());
      assertEquals("Success", HttpStatusCode.SUCCESS.getDescription());
      assertEquals("success", HttpStatusCode.SUCCESS.getPath());
    }

    @Test
    @DisplayName("BAD_REQUEST should have value 400 and correct description")
    void badRequestShouldHaveCorrectValues() {
      assertEquals(400, HttpStatusCode.BAD_REQUEST.getValue());
      assertEquals("Bad Request", HttpStatusCode.BAD_REQUEST.getDescription());
      assertEquals("badRequest", HttpStatusCode.BAD_REQUEST.getPath());
    }

    @Test
    @DisplayName("UNAUTHORIZED should have value 401")
    void unauthorizedShouldHaveCorrectValue() {
      assertEquals(401, HttpStatusCode.UNAUTHORIZED.getValue());
      assertEquals("Not Authorized", HttpStatusCode.UNAUTHORIZED.getDescription());
    }

    @Test
    @DisplayName("FORBIDDEN should have value 403")
    void forbiddenShouldHaveCorrectValue() {
      assertEquals(403, HttpStatusCode.FORBIDDEN.getValue());
      assertEquals("Forbidden", HttpStatusCode.FORBIDDEN.getDescription());
    }

    @Test
    @DisplayName("NOT_FOUND should have value 404")
    void notFoundShouldHaveCorrectValue() {
      assertEquals(404, HttpStatusCode.NOT_FOUND.getValue());
      assertEquals("Not Found", HttpStatusCode.NOT_FOUND.getDescription());
    }

    @Test
    @DisplayName("INTERNAL_SERVER_ERROR should have value 500")
    void internalServerErrorShouldHaveCorrectValue() {
      assertEquals(500, HttpStatusCode.INTERNAL_SERVER_ERROR.getValue());
      assertEquals("Internal Server Error", HttpStatusCode.INTERNAL_SERVER_ERROR.getDescription());
    }

    @Test
    @DisplayName("NO_CONTENT should have value 204")
    void noContentShouldHaveCorrectValue() {
      assertEquals(204, HttpStatusCode.NO_CONTENT.getValue());
      assertEquals("No Content", HttpStatusCode.NO_CONTENT.getDescription());
    }

    @Test
    @DisplayName("CREATED should have value 201")
    void createdShouldHaveCorrectValue() {
      assertEquals(201, HttpStatusCode.CREATED.getValue());
      assertEquals("Created", HttpStatusCode.CREATED.getDescription());
      assertEquals("success", HttpStatusCode.CREATED.getPath());
    }
  }

  @Nested
  @DisplayName("getByValue Tests")
  class GetByValueTests {

    @Test
    @DisplayName("getByValue(200) should return SUCCESS")
    void getByValue200ShouldReturnSuccess() {
      HttpStatusCode result = HttpStatusCode.getByValue(200);
      assertEquals(HttpStatusCode.SUCCESS, result);
    }

    @Test
    @DisplayName("getByValue(404) should return NOT_FOUND")
    void getByValue404ShouldReturnNotFound() {
      HttpStatusCode result = HttpStatusCode.getByValue(404);
      assertEquals(HttpStatusCode.NOT_FOUND, result);
    }

    @Test
    @DisplayName("getByValue(500) should return INTERNAL_SERVER_ERROR")
    void getByValue500ShouldReturnInternalServerError() {
      HttpStatusCode result = HttpStatusCode.getByValue(500);
      assertEquals(HttpStatusCode.INTERNAL_SERVER_ERROR, result);
    }

    @Test
    @DisplayName("getByValue with unknown code should throw IllegalArgumentException")
    void getByValueWithUnknownCodeShouldThrow() {
      IllegalArgumentException ex =
          assertThrows(IllegalArgumentException.class, () -> HttpStatusCode.getByValue(999));
      assertTrue(ex.getMessage().contains("999"));
    }
  }

  @Nested
  @DisplayName("toString Tests")
  class ToStringTests {

    @Test
    @DisplayName("toString should return value and description")
    void toStringShouldReturnValueAndDescription() {
      assertEquals("200 Success", HttpStatusCode.SUCCESS.toString());
      assertEquals("404 Not Found", HttpStatusCode.NOT_FOUND.toString());
      assertEquals("500 Internal Server Error", HttpStatusCode.INTERNAL_SERVER_ERROR.toString());
    }
  }

  @Nested
  @DisplayName("Path Tests")
  class PathTests {

    @Test
    @DisplayName("FORBIDDEN variants should have different paths")
    void forbiddenVariantsShouldHaveDifferentPaths() {
      assertEquals("forbidden", HttpStatusCode.FORBIDDEN.getPath());
      assertEquals("no-access", HttpStatusCode.FORBIDDEN_NO_ACCESS.getPath());
      assertEquals("access-pending", HttpStatusCode.FORBIDDEN_ACCESS_PENDING.getPath());
      assertEquals("access-rejected", HttpStatusCode.FORBIDDEN_ACCESS_REJECTED.getPath());
    }
  }
}
