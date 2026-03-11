package org.cdpg.dx.common.exception;

import static org.junit.jupiter.api.Assertions.*;

import io.vertx.serviceproxy.ServiceException;
import org.cdpg.dx.auth.authentication.exception.AuthenticationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Exception Classes Tests")
class ExceptionTest {

  @Nested
  @DisplayName("BaseDxException Tests")
  class BaseDxExceptionTests {

    @Test
    @DisplayName("should create with failure code and message")
    void shouldCreateWithFailureCodeAndMessage() {
      BaseDxException ex = new BaseDxException(404, "Not found");
      assertEquals(404, ex.failureCode());
      assertEquals("Not found", ex.getMessage());
    }

    @Test
    @DisplayName("should create with message only using DEFAULT_CODE")
    void shouldCreateWithMessageOnly() {
      BaseDxException ex = new BaseDxException("Something went wrong");
      assertEquals(DxErrorCodes.DEFAULT_CODE, ex.failureCode());
      assertEquals("Something went wrong", ex.getMessage());
    }

    @Test
    @DisplayName("should create with failure code, message, and cause without throwing")
    void shouldCreateWithCause() {
      RuntimeException cause = new RuntimeException("root cause");
      BaseDxException ex = new BaseDxException(500, "Server error", cause);
      assertEquals(500, ex.failureCode());
      assertEquals("Server error", ex.getMessage());
      // Note: ServiceException hierarchy prevents initCause from working,
      // but the constructor should not throw
    }

    @Test
    @DisplayName("from() should return same instance if already BaseDxException")
    void fromShouldReturnSameIfBaseDxException() {
      BaseDxException original = new BaseDxException(400, "bad");
      BaseDxException result = BaseDxException.from(original);
      assertSame(original, result);
    }

    @Test
    @DisplayName("from() should wrap generic Throwable with DEFAULT_CODE")
    void fromShouldWrapGenericThrowable() {
      RuntimeException generic = new RuntimeException("generic error");
      BaseDxException result = BaseDxException.from(generic);
      assertEquals(DxErrorCodes.DEFAULT_CODE, result.failureCode());
      assertEquals("generic error", result.getMessage());
    }

    @Test
    @DisplayName("from() should convert ServiceException with PG_NO_ROW_ERROR to NoRowFoundException")
    void fromShouldConvertPgNoRowError() {
      ServiceException se = new ServiceException(DxErrorCodes.PG_NO_ROW_ERROR, "no rows");
      BaseDxException result = BaseDxException.from(se);
      assertInstanceOf(NoRowFoundException.class, result);
      assertEquals("no rows", result.getMessage());
    }

    @Test
    @DisplayName("from() should convert ServiceException with PG_INVALID_COL_ERROR to InvalidColumnNameException")
    void fromShouldConvertPgInvalidColError() {
      ServiceException se = new ServiceException(DxErrorCodes.PG_INVALID_COL_ERROR, "bad column");
      BaseDxException result = BaseDxException.from(se);
      assertInstanceOf(InvalidColumnNameException.class, result);
    }

    @Test
    @DisplayName("from() should convert ServiceException with PG_UNIQUE_CONSTRAINT_VIOLATION_ERROR")
    void fromShouldConvertUniqueConstraintViolation() {
      ServiceException se =
          new ServiceException(DxErrorCodes.PG_UNIQUE_CONSTRAINT_VIOLATION_ERROR, "duplicate key");
      BaseDxException result = BaseDxException.from(se);
      assertInstanceOf(UniqueConstraintViolationException.class, result);
    }

    @Test
    @DisplayName("from() should convert ServiceException with PG_ERROR to DxPgException")
    void fromShouldConvertPgError() {
      ServiceException se = new ServiceException(DxErrorCodes.PG_ERROR, "pg error");
      BaseDxException result = BaseDxException.from(se);
      assertInstanceOf(DxPgException.class, result);
    }

    @Test
    @DisplayName("from() should wrap unknown ServiceException with DEFAULT_CODE")
    void fromShouldWrapUnknownServiceException() {
      ServiceException se = new ServiceException(99999, "unknown");
      BaseDxException result = BaseDxException.from(se);
      assertInstanceOf(BaseDxException.class, result);
      assertEquals(DxErrorCodes.DEFAULT_CODE, result.failureCode());
    }
  }

  @Nested
  @DisplayName("Specific Exception Subclass Tests")
  class SpecificExceptionTests {

    @Test
    @DisplayName("DxBadRequestException should use BAD_REQUEST error code")
    void dxBadRequestExceptionShouldUseBadRequestCode() {
      DxBadRequestException ex = new DxBadRequestException("invalid input");
      assertEquals(DxErrorCodes.BAD_REQUEST, ex.failureCode());
      assertEquals("invalid input", ex.getMessage());
      assertInstanceOf(BaseDxException.class, ex);
    }

    @Test
    @DisplayName("DxNotFoundException should use NOT_FOUND error code")
    void dxNotFoundExceptionShouldUseNotFoundCode() {
      DxNotFoundException ex = new DxNotFoundException("resource missing");
      assertEquals(DxErrorCodes.NOT_FOUND, ex.failureCode());
      assertEquals("resource missing", ex.getMessage());
    }

    @Test
    @DisplayName("DxForbiddenException should use FORBIDDEN error code")
    void dxForbiddenExceptionShouldUseForbiddenCode() {
      DxForbiddenException ex = new DxForbiddenException("access denied");
      assertEquals(DxErrorCodes.FORBIDDEN, ex.failureCode());
      assertEquals("access denied", ex.getMessage());
    }

    @Test
    @DisplayName("DxUnauthorizedException should use UNAUTHORIZED error code")
    void dxUnauthorizedExceptionShouldUseUnauthorizedCode() {
      DxUnauthorizedException ex = new DxUnauthorizedException("not authenticated");
      assertEquals(DxErrorCodes.UNAUTHORIZED, ex.failureCode());
      assertEquals("not authenticated", ex.getMessage());
    }

    @Test
    @DisplayName("DxValidationException should use VALIDATION_ERROR code")
    void dxValidationExceptionShouldUseValidationErrorCode() {
      DxValidationException ex = new DxValidationException("invalid field");
      assertEquals(DxErrorCodes.VALIDATION_ERROR, ex.failureCode());
      assertEquals("invalid field", ex.getMessage());
    }

    @Test
    @DisplayName("DxConflictException should use CONFLICT error code")
    void dxConflictExceptionShouldUseConflictCode() {
      DxConflictException ex = new DxConflictException("resource already exists");
      assertEquals(DxErrorCodes.CONFLICT, ex.failureCode());
      assertEquals("resource already exists", ex.getMessage());
    }

    @Test
    @DisplayName("DxInternalServerErrorException should use INTERNAL_ERROR code")
    void dxInternalServerErrorExceptionShouldUseInternalErrorCode() {
      DxInternalServerErrorException ex = new DxInternalServerErrorException("server crash");
      assertEquals(DxErrorCodes.INTERNAL_ERROR, ex.failureCode());
      assertEquals("server crash", ex.getMessage());
    }

    @Test
    @DisplayName("DxAuthException should use AUTH_ERROR code")
    void dxAuthExceptionShouldUseAuthErrorCode() {
      DxAuthException ex = new DxAuthException("auth failure");
      assertEquals(DxErrorCodes.AUTH_ERROR, ex.failureCode());
      assertEquals("auth failure", ex.getMessage());
    }

    @Test
    @DisplayName("DxTokenInvalidException should use TOKEN_INVALID code")
    void dxTokenInvalidExceptionShouldUseTokenInvalidCode() {
      DxTokenInvalidException ex = new DxTokenInvalidException("expired token");
      assertEquals(DxErrorCodes.TOKEN_INVALID, ex.failureCode());
      assertEquals("expired token", ex.getMessage());
    }

    @Test
    @DisplayName("AuthenticationException should use UNAUTHORIZED code")
    void authenticationExceptionShouldUseUnauthorizedCode() {
      AuthenticationException ex = new AuthenticationException("invalid credentials");
      assertEquals(DxErrorCodes.UNAUTHORIZED, ex.failureCode());
      assertEquals("invalid credentials", ex.getMessage());
      assertInstanceOf(BaseDxException.class, ex);
    }

    @Test
    @DisplayName("DxPgException should use PG_ERROR code")
    void dxPgExceptionShouldUsePgErrorCode() {
      DxPgException ex = new DxPgException("database error");
      assertEquals(DxErrorCodes.PG_ERROR, ex.failureCode());
      assertEquals("database error", ex.getMessage());
    }

    @Test
    @DisplayName("NoRowFoundException should use PG_NO_ROW_ERROR code and extend DxPgException")
    void noRowFoundExceptionShouldUsePgNoRowErrorCode() {
      NoRowFoundException ex = new NoRowFoundException("no rows");
      assertEquals(DxErrorCodes.PG_NO_ROW_ERROR, ex.failureCode());
      assertInstanceOf(DxPgException.class, ex);
    }

    @Test
    @DisplayName("InvalidColumnNameException should use PG_INVALID_COL_ERROR code")
    void invalidColumnNameExceptionShouldUsePgInvalidColErrorCode() {
      InvalidColumnNameException ex = new InvalidColumnNameException("bad column");
      assertEquals(DxErrorCodes.PG_INVALID_COL_ERROR, ex.failureCode());
      assertInstanceOf(DxPgException.class, ex);
    }

    @Test
    @DisplayName("UniqueConstraintViolationException should use PG_UNIQUE_CONSTRAINT_VIOLATION_ERROR code")
    void uniqueConstraintViolationExceptionShouldUseCorrectCode() {
      UniqueConstraintViolationException ex =
          new UniqueConstraintViolationException("duplicate key");
      assertEquals(DxErrorCodes.PG_UNIQUE_CONSTRAINT_VIOLATION_ERROR, ex.failureCode());
      assertInstanceOf(DxPgException.class, ex);
    }

    @Test
    @DisplayName("DxEsException should use ES_ERROR code")
    void dxEsExceptionShouldUseEsErrorCode() {
      DxEsException ex = new DxEsException("elasticsearch error");
      assertEquals(DxErrorCodes.ES_ERROR, ex.failureCode());
      assertEquals("elasticsearch error", ex.getMessage());
    }

    @Test
    @DisplayName("DxSubscriptionException should use SUBS_ERROR code")
    void dxSubscriptionExceptionShouldUseSubsErrorCode() {
      DxSubscriptionException ex = new DxSubscriptionException("subscription error");
      assertEquals(DxErrorCodes.SUBS_ERROR, ex.failureCode());
      assertEquals("subscription error", ex.getMessage());
    }

    @Test
    @DisplayName("QueueAlreadyExistsException should use SUBS_QUEUE_EXISTS code and extend DxSubscriptionException")
    void queueAlreadyExistsExceptionShouldUseCorrectCode() {
      QueueAlreadyExistsException ex = new QueueAlreadyExistsException("queue exists");
      assertEquals(DxErrorCodes.SUBS_QUEUE_EXISTS, ex.failureCode());
      assertInstanceOf(DxSubscriptionException.class, ex);
    }

    @Test
    @DisplayName("DxRabbitMqException should use RABBIT_MQ_ERROR code")
    void dxRabbitMqExceptionShouldUseCorrectCode() {
      DxRabbitMqException ex = new DxRabbitMqException("rabbitmq error");
      assertEquals(DxErrorCodes.RABBIT_MQ_ERROR, ex.failureCode());
      assertEquals("rabbitmq error", ex.getMessage());
    }

    @Test
    @DisplayName("QueueNotFoundException should use SUBS_QUEUE_NOT_FOUND code and extend DxRabbitMqException")
    void queueNotFoundExceptionShouldUseCorrectCode() {
      QueueNotFoundException ex = new QueueNotFoundException("queue not found");
      assertEquals(DxErrorCodes.SUBS_QUEUE_NOT_FOUND, ex.failureCode());
      assertInstanceOf(DxRabbitMqException.class, ex);
    }

    @Test
    @DisplayName("DxForbiddenNoAccessException should use FORBIDDEN_NO_ACCESS code")
    void dxForbiddenNoAccessExceptionShouldUseCorrectCode() {
      DxForbiddenNoAccessException ex = new DxForbiddenNoAccessException("no access");
      assertEquals(DxErrorCodes.FORBIDDEN_NO_ACCESS, ex.failureCode());
    }

    @Test
    @DisplayName("DxForbiddenPendingAccessException should use FORBIDDEN_ACCESS_PENDING code")
    void dxForbiddenPendingAccessExceptionShouldUseCorrectCode() {
      DxForbiddenPendingAccessException ex =
          new DxForbiddenPendingAccessException("access pending");
      assertEquals(DxErrorCodes.FORBIDDEN_ACCESS_PENDING, ex.failureCode());
    }

    @Test
    @DisplayName("DxForbiddenAccessRejectedException should use FORBIDDEN_ACCESS_REJECTED code")
    void dxForbiddenAccessRejectedExceptionShouldUseCorrectCode() {
      DxForbiddenAccessRejectedException ex =
          new DxForbiddenAccessRejectedException("access rejected");
      assertEquals(DxErrorCodes.FORBIDDEN_ACCESS_REJECTED, ex.failureCode());
    }

    @Test
    @DisplayName("CsvLimitExceedNoRecordFound should use CSV_STREAM_ERROR code")
    void csvLimitExceedNoRecordFoundShouldUseCorrectCode() {
      CsvLimitExceedNoRecordFound ex = new CsvLimitExceedNoRecordFound("csv limit exceeded");
      assertEquals(DxErrorCodes.CSV_STREAM_ERROR, ex.failureCode());
    }

    @Test
    @DisplayName("KeycloakServiceException should use KEYCLOAK_SERVICE_ERROR code")
    void keycloakServiceExceptionShouldUseCorrectCode() {
      KeycloakServiceException ex = new KeycloakServiceException("keycloak error");
      assertEquals(12100, ex.failureCode());
      assertEquals("keycloak error", ex.getMessage());
    }

    @Test
    @DisplayName("DxNotAcceptableException should use NOT_ACCEPTABLE code")
    void dxNotAcceptableExceptionShouldUseCorrectCode() {
      DxNotAcceptableException ex = new DxNotAcceptableException("not acceptable");
      assertEquals(DxErrorCodes.NOT_ACCEPTABLE, ex.failureCode());
    }

    @Test
    @DisplayName("DxTooManyRequestsException should use TOO_MANY_REQUESTS code")
    void dxTooManyRequestsExceptionShouldUseCorrectCode() {
      DxTooManyRequestsException ex = new DxTooManyRequestsException("rate limited");
      assertEquals(DxErrorCodes.TOO_MANY_REQUESTS, ex.failureCode());
    }

    @Test
    @DisplayName("DxTimeOutException should use TIMEOUT_ERROR code")
    void dxTimeOutExceptionShouldUseCorrectCode() {
      DxTimeOutException ex = new DxTimeOutException("timed out");
      assertEquals(DxErrorCodes.TIMEOUT_ERROR, ex.failureCode());
    }
  }

  @Nested
  @DisplayName("Exception Constructor With Cause Tests")
  class ConstructorWithCauseTests {

    @Test
    @DisplayName("DxBadRequestException two-arg constructor should set correct code and message")
    void dxBadRequestExceptionWithCauseShouldSetCodeAndMessage() {
      RuntimeException cause = new RuntimeException("root");
      DxBadRequestException ex = new DxBadRequestException("bad request", cause);
      assertEquals(DxErrorCodes.BAD_REQUEST, ex.failureCode());
      assertEquals("bad request", ex.getMessage());
    }

    @Test
    @DisplayName("DxNotFoundException two-arg constructor should set correct code and message")
    void dxNotFoundExceptionWithCauseShouldSetCodeAndMessage() {
      RuntimeException cause = new RuntimeException("root");
      DxNotFoundException ex = new DxNotFoundException("not found", cause);
      assertEquals(DxErrorCodes.NOT_FOUND, ex.failureCode());
      assertEquals("not found", ex.getMessage());
    }

    @Test
    @DisplayName("DxConflictException two-arg constructor should set correct code and message")
    void dxConflictExceptionWithCauseShouldSetCodeAndMessage() {
      RuntimeException cause = new RuntimeException("root");
      DxConflictException ex = new DxConflictException("conflict", cause);
      assertEquals(DxErrorCodes.CONFLICT, ex.failureCode());
      assertEquals("conflict", ex.getMessage());
    }
  }

  @Nested
  @DisplayName("DxErrorCodes Constants Tests")
  class DxErrorCodesTests {

    @Test
    @DisplayName("should have expected constant values")
    void shouldHaveExpectedConstantValues() {
      assertEquals(10000, DxErrorCodes.DEFAULT_CODE);
      assertEquals(10001, DxErrorCodes.VALIDATION_ERROR);
      assertEquals(10002, DxErrorCodes.NOT_FOUND);
      assertEquals(10004, DxErrorCodes.INTERNAL_ERROR);
      assertEquals(10005, DxErrorCodes.AUTH_ERROR);
      assertEquals(10006, DxErrorCodes.BAD_REQUEST);
      assertEquals(10007, DxErrorCodes.NOT_ACCEPTABLE);
    }

    @Test
    @DisplayName("should have expected PG error codes")
    void shouldHaveExpectedPgErrorCodes() {
      assertEquals(11000, DxErrorCodes.PG_ERROR);
      assertEquals(11001, DxErrorCodes.PG_NO_ROW_ERROR);
      assertEquals(11002, DxErrorCodes.PG_INVALID_COL_ERROR);
      assertEquals(11003, DxErrorCodes.PG_UNIQUE_CONSTRAINT_VIOLATION_ERROR);
    }

    @Test
    @DisplayName("should have expected auth error codes")
    void shouldHaveExpectedAuthErrorCodes() {
      assertEquals(12000, DxErrorCodes.UNAUTHORIZED);
      assertEquals(12001, DxErrorCodes.FORBIDDEN);
      assertEquals(12002, DxErrorCodes.TOKEN_INVALID);
      assertEquals(12100, DxErrorCodes.KEYCLOAK_SERVICE_ERROR);
    }
  }
}
