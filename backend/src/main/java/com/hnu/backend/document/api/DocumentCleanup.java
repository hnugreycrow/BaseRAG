package com.hnu.backend.document.api;

import java.util.List;
import java.util.UUID;

/** 向删除编排公开知识库关联文档的清理能力。 */
public interface DocumentCleanup {
  /**
   * 收集待删除知识库的对象存储键。
   *
   * @param knowledgeBaseId 非空且已授权的知识库标识
   * @return 文件键列表，无文件时为空
   */
  List<String> storageKeys(UUID knowledgeBaseId);

  /**
   * 在调用方事务中按外键顺序删除文档记录。
   *
   * @param knowledgeBaseId 非空且已授权的知识库标识
   * @throws com.hnu.backend.common.exception.ApiException 存在正在处理的文档时拒绝删除
   */
  void deleteRecords(UUID knowledgeBaseId);

  /**
   * 数据库实际提交后逐个尽力删除文件，单个失败仅记录日志并继续。
   *
   * @param storageKeys 已提交删除对应的文件键列表
   */
  void removeStoredFiles(List<String> storageKeys);
}
