package com.hnu.backend.document.storage;

/** 文档原始文件的统一存储接口。 */
public interface FileStorage {
  /**
   * 保存文件内容。
   *
   * @param key 存储对象键
   * @param content 文件二进制内容
   */
  void put(String key, byte[] content);

  /**
   * 读取文件内容。
   *
   * @param key 存储对象键
   * @return 文件二进制内容
   */
  byte[] get(String key);

  /**
   * 删除文件。
   *
   * @param key 存储对象键
   */
  void remove(String key);
}
