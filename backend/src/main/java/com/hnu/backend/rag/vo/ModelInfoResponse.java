package com.hnu.backend.rag.vo;

/**
 * 实际执行生成任务的模型信息。
 *
 * @param id 模型配置标识
 * @param provider 模型供应商
 * @param model 模型名称
 */
public record ModelInfoResponse(String id, String provider, String model) {}
