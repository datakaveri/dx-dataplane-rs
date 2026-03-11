package org.cdpg.dx.common.util;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RequestBody;
import io.vertx.ext.web.RoutingContext;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.cdpg.dx.common.exception.DxValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("RequestHelper Tests")
class RequestHelperTest {

  @Nested
  @DisplayName("getPathParamAsUUID Tests")
  class GetPathParamAsUuidTests {

    @Test
    @DisplayName("should return UUID for valid path parameter")
    void shouldReturnUuidForValidPathParam() {
      RoutingContext ctx = mock(RoutingContext.class);
      UUID expected = UUID.randomUUID();
      when(ctx.pathParam("id")).thenReturn(expected.toString());

      UUID result = RequestHelper.getPathParamAsUUID(ctx, "id");
      assertEquals(expected, result);
    }

    @Test
    @DisplayName("should throw DxValidationException for null path parameter")
    void shouldThrowForNullPathParam() {
      RoutingContext ctx = mock(RoutingContext.class);
      when(ctx.pathParam("id")).thenReturn(null);

      DxValidationException ex =
          assertThrows(
              DxValidationException.class, () -> RequestHelper.getPathParamAsUUID(ctx, "id"));
      assertTrue(ex.getMessage().contains("Missing required path param"));
    }

    @Test
    @DisplayName("should throw DxValidationException for blank path parameter")
    void shouldThrowForBlankPathParam() {
      RoutingContext ctx = mock(RoutingContext.class);
      when(ctx.pathParam("id")).thenReturn("   ");

      DxValidationException ex =
          assertThrows(
              DxValidationException.class, () -> RequestHelper.getPathParamAsUUID(ctx, "id"));
      assertTrue(ex.getMessage().contains("Missing required path param"));
    }

    @Test
    @DisplayName("should throw DxValidationException for invalid UUID format")
    void shouldThrowForInvalidUuidFormat() {
      RoutingContext ctx = mock(RoutingContext.class);
      when(ctx.pathParam("id")).thenReturn("not-a-uuid");

      DxValidationException ex =
          assertThrows(
              DxValidationException.class, () -> RequestHelper.getPathParamAsUUID(ctx, "id"));
      assertTrue(ex.getMessage().contains("Invalid UUID format"));
    }
  }

  @Nested
  @DisplayName("getPathParam Tests")
  class GetPathParamTests {

    @Test
    @DisplayName("should return Optional with value when param exists")
    void shouldReturnOptionalWithValue() {
      RoutingContext ctx = mock(RoutingContext.class);
      when(ctx.pathParam("name")).thenReturn("testValue");

      Optional<String> result = RequestHelper.getPathParam(ctx, "name");
      assertTrue(result.isPresent());
      assertEquals("testValue", result.get());
    }

    @Test
    @DisplayName("should return empty Optional when param is null")
    void shouldReturnEmptyOptionalWhenNull() {
      RoutingContext ctx = mock(RoutingContext.class);
      when(ctx.pathParam("name")).thenReturn(null);

      Optional<String> result = RequestHelper.getPathParam(ctx, "name");
      assertTrue(result.isEmpty());
    }
  }

  @Nested
  @DisplayName("getBodyJson Tests")
  class GetBodyJsonTests {

    @Test
    @DisplayName("should return body JSON when present")
    void shouldReturnBodyJsonWhenPresent() {
      RoutingContext ctx = mock(RoutingContext.class);
      RequestBody body = mock(RequestBody.class);
      JsonObject json = new JsonObject().put("key", "value");
      when(ctx.body()).thenReturn(body);
      when(body.asJsonObject()).thenReturn(json);

      JsonObject result = RequestHelper.getBodyJson(ctx);
      assertEquals("value", result.getString("key"));
    }

    @Test
    @DisplayName("should return empty JsonObject when body is null")
    void shouldReturnEmptyJsonObjectWhenBodyIsNull() {
      RoutingContext ctx = mock(RoutingContext.class);
      RequestBody body = mock(RequestBody.class);
      when(ctx.body()).thenReturn(body);
      when(body.asJsonObject()).thenReturn(null);

      JsonObject result = RequestHelper.getBodyJson(ctx);
      assertNotNull(result);
      assertTrue(result.isEmpty());
    }
  }

  @Nested
  @DisplayName("parseAndValidateBody Tests")
  class ParseAndValidateBodyTests {

    @Test
    @DisplayName("should parse body when all required keys are present")
    void shouldParseBodyWhenRequiredKeysPresent() {
      RoutingContext ctx = mock(RoutingContext.class);
      RequestBody body = mock(RequestBody.class);
      JsonObject json = new JsonObject().put("name", "Alice").put("age", 30);
      when(ctx.body()).thenReturn(body);
      when(body.asJsonObject()).thenReturn(json);

      String result =
          RequestHelper.parseAndValidateBody(
              ctx, Set.of("name", "age"), j -> j.getString("name"));
      assertEquals("Alice", result);
    }

    @Test
    @DisplayName("should throw when required key is missing from body")
    void shouldThrowWhenRequiredKeyIsMissing() {
      RoutingContext ctx = mock(RoutingContext.class);
      RequestBody body = mock(RequestBody.class);
      JsonObject json = new JsonObject().put("name", "Alice");
      when(ctx.body()).thenReturn(body);
      when(body.asJsonObject()).thenReturn(json);

      assertThrows(
          IllegalArgumentException.class,
          () -> RequestHelper.parseAndValidateBody(ctx, Set.of("name", "email"), j -> j));
    }
  }

  @Nested
  @DisplayName("mergeBodyAndParse Tests")
  class MergeBodyAndParseTests {

    @Test
    @DisplayName("should merge extra fields into body and parse")
    void shouldMergeExtraFieldsAndParse() {
      RoutingContext ctx = mock(RoutingContext.class);
      RequestBody body = mock(RequestBody.class);
      JsonObject json = new JsonObject().put("name", "Alice");
      when(ctx.body()).thenReturn(body);
      when(body.asJsonObject()).thenReturn(json);

      String result =
          RequestHelper.mergeBodyAndParse(
              ctx, Map.of("role", "admin"), j -> j.getString("role"));
      assertEquals("admin", result);
    }

    @Test
    @DisplayName("should handle null extra map")
    void shouldHandleNullExtraMap() {
      RoutingContext ctx = mock(RoutingContext.class);
      RequestBody body = mock(RequestBody.class);
      JsonObject json = new JsonObject().put("name", "Bob");
      when(ctx.body()).thenReturn(body);
      when(body.asJsonObject()).thenReturn(json);

      String result =
          RequestHelper.mergeBodyAndParse(ctx, null, j -> j.getString("name"));
      assertEquals("Bob", result);
    }
  }
}
