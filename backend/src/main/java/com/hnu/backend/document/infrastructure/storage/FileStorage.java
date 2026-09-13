package com.hnu.backend.document.infrastructure.storage;

public interface FileStorage {
  void put(String key, byte[] content);

  byte[] get(String key);

  void remove(String key);
}
