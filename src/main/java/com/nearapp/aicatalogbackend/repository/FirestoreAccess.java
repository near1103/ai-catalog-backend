package com.nearapp.aicatalogbackend.repository;

import com.google.api.core.ApiFuture;
import com.nearapp.aicatalogbackend.exception.ApiException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class FirestoreAccess {
  private FirestoreAccess() {}

  static <T> T await(ApiFuture<T> future) {
    try {
      return future.get(30, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ApiException.external("Database request interrupted");
    } catch (ExecutionException e) {
      for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
        if (cause instanceof ApiException failure) throw failure;
      }
      throw ApiException.external("Database unavailable");
    } catch (TimeoutException e) {
      throw ApiException.external("Database unavailable");
    }
  }

  static void id(String id) {
    if (id == null || !id.matches("[A-Za-z0-9_-]{1,100}"))
      throw ApiException.bad("Invalid identifier");
  }
}
