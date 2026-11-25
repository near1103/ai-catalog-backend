package com.nearapp.aicatalogbackend.exception;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
  private final HttpStatus status;

  public ApiException(HttpStatus status, String message) {
    super(message);
    this.status = status;
  }

  public HttpStatus status() {
    return status;
  }

  public static ApiException bad(String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, message);
  }

  public static ApiException missing() {
    return new ApiException(HttpStatus.NOT_FOUND, "Resource not found");
  }

  public static ApiException external(String message) {
    return new ApiException(HttpStatus.BAD_GATEWAY, message);
  }

  public static ApiException conflict(String message) {
    return new ApiException(HttpStatus.CONFLICT, message);
  }
}
