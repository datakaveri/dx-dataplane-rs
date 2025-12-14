package org.cdpg.dx.common.exception;

public class DxNotAcceptableException extends BaseDxException {
  public DxNotAcceptableException(String message) {
      super(DxErrorCodes.NOT_ACCEPTABLE, message);
  }

    public DxNotAcceptableException(String message, Throwable cause) {
        super(DxErrorCodes.NOT_ACCEPTABLE, message, cause);
    }
}
