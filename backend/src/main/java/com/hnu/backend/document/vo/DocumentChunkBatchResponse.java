package com.hnu.backend.document.vo;

import java.util.List;
import java.util.UUID;

/**
 * 批量提交中接受和跳过的文档标识，顺序与请求中的对应项一致。
 *
 * @param acceptedDocumentIds 本次成功认领并准备入队的文档
 * @param skippedDocumentIds 调用方允许跳过且最新版本已在处理的文档
 */
public record DocumentChunkBatchResponse(
    List<UUID> acceptedDocumentIds, List<UUID> skippedDocumentIds) {}
