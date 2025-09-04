package org.cdpg.dx.common.exception;

public class DxTimeOutException extends BaseDxException {
  public DxTimeOutException(String message) {
    super(DxErrorCodes.INTERNAL_ERROR, message);
  }
    public DxTimeOutException(String message, Throwable cause) {
        super(DxErrorCodes.INTERNAL_ERROR, message, cause);
    }
}
