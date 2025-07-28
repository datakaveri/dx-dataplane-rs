package org.cdpg.dx.auth.authentication.exception;

import static org.cdpg.dx.common.exception.DxErrorCodes.UNAUTHORIZED;

import org.cdpg.dx.common.exception.BaseDxException;

public class AuthenticationException extends BaseDxException {
  public AuthenticationException(String message) {
    super(UNAUTHORIZED, message);
  }
}
