package com.hnu.backend.model.client;

/**
 * 与供应商无关的一次聊天生成请求。
 *
 * @param system 服务端可信系统提示词
 * @param user 本轮用户提示词
 * @param thinkingEnabled 是否向支持的模型请求思考模式
 */
public record ChatGenerationRequest(String system, String user, boolean thinkingEnabled) {}
