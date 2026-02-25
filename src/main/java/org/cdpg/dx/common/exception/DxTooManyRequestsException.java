package org.cdpg.dx.common.exception;

public class DxTooManyRequestsException extends BaseDxException {
  public DxTooManyRequestsException(String message) {
    super(DxErrorCodes.TOO_MANY_REQUESTS, message);
  }

  public DxTooManyRequestsException(String message, Throwable cause) {
    super(DxErrorCodes.TOO_MANY_REQUESTS, message, cause);
  }
}
