package org.cdpg.dx.auth.authentication.util;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("BearerTokenExtractor Tests")
class BearerTokenExtractorTest {

  private RoutingContext mockContext(String authorizationHeader) {
    RoutingContext ctx = mock(RoutingContext.class);
    HttpServerRequest request = mock(HttpServerRequest.class);
    when(ctx.request()).thenReturn(request);
    when(request.getHeader("Authorization")).thenReturn(authorizationHeader);
    return ctx;
  }

  @Nested
  @DisplayName("extract() Tests")
  class ExtractTests {

    @Test
    @DisplayName("should extract token from valid Bearer header")
    void shouldExtractTokenFromValidBearerHeader() {
      RoutingContext ctx = mockContext("Bearer mySecretToken123");
      String token = BearerTokenExtractor.extract(ctx);
      assertEquals("mySecretToken123", token);
    }

    @Test
    @DisplayName("should return null when Authorization header is missing")
    void shouldReturnNullWhenHeaderMissing() {
      RoutingContext ctx = mockContext(null);
      String token = BearerTokenExtractor.extract(ctx);
      assertNull(token);
    }

    @Test
    @DisplayName("should return null when Authorization header does not start with Bearer")
    void shouldReturnNullWhenNotBearerScheme() {
      RoutingContext ctx = mockContext("Basic dXNlcjpwYXNz");
      String token = BearerTokenExtractor.extract(ctx);
      assertNull(token);
    }

    @Test
    @DisplayName("should trim whitespace from extracted token")
    void shouldTrimWhitespaceFromToken() {
      RoutingContext ctx = mockContext("Bearer   tokenWithSpaces   ");
      String token = BearerTokenExtractor.extract(ctx);
      assertEquals("tokenWithSpaces", token);
    }

    @Test
    @DisplayName("should handle empty string after Bearer prefix")
    void shouldHandleEmptyTokenAfterBearer() {
      RoutingContext ctx = mockContext("Bearer ");
      String token = BearerTokenExtractor.extract(ctx);
      assertEquals("", token);
    }

    @Test
    @DisplayName("should be case-sensitive for Bearer prefix")
    void shouldBeCaseSensitiveForBearerPrefix() {
      RoutingContext ctx = mockContext("bearer myToken");
      String token = BearerTokenExtractor.extract(ctx);
      assertNull(token);
    }

    @Test
    @DisplayName("should handle token with special characters")
    void shouldHandleTokenWithSpecialCharacters() {
      String jwt =
          "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.signature";
      RoutingContext ctx = mockContext("Bearer " + jwt);
      String token = BearerTokenExtractor.extract(ctx);
      assertEquals(jwt, token);
    }
  }
}
