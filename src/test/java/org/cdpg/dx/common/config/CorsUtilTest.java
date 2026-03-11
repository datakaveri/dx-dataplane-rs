package org.cdpg.dx.common.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("CorsUtil Tests")
class CorsUtilTest {

  @AfterEach
  void tearDown() {
    CorsUtil.allowedOrigins = null;
  }

  @Nested
  @DisplayName("allowedOrigins field Tests")
  class AllowedOriginsTests {

    @Test
    @DisplayName("allowedOrigins should be null by default")
    void allowedOriginsShouldBeNullByDefault() {
      CorsUtil.allowedOrigins = null;
      assertNull(CorsUtil.allowedOrigins);
    }

    @Test
    @DisplayName("allowedOrigins should accept a list of origins")
    void allowedOriginsShouldAcceptListOfOrigins() {
      CorsUtil.allowedOrigins = List.of("http://localhost:3000", "https://example.com");
      assertNotNull(CorsUtil.allowedOrigins);
      assertEquals(2, CorsUtil.allowedOrigins.size());
      assertTrue(CorsUtil.allowedOrigins.contains("http://localhost:3000"));
      assertTrue(CorsUtil.allowedOrigins.contains("https://example.com"));
    }

    @Test
    @DisplayName("allowedOrigins should support wildcard")
    void allowedOriginsShouldSupportWildcard() {
      CorsUtil.allowedOrigins = List.of("*");
      assertTrue(CorsUtil.allowedOrigins.contains("*"));
      assertEquals(1, CorsUtil.allowedOrigins.size());
    }
  }
}
