package org.cdpg.dx.common.exception;

public class DxTimeOutException extends BaseDxException {
  public DxTimeOutException(String message) {
    super(DxErrorCodes.TIMEOUT_ERROR, message);
  }

  public DxTimeOutException(String message, Throwable cause) {
    super(DxErrorCodes.TIMEOUT_ERROR, message, cause);
  }
}
