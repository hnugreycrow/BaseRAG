package com.hnu.backend.model.client;

/** 与供应商无关的一次聊天生成请求。 */
public record ChatGenerationRequest(String system, String user, boolean thinkingEnabled) {}
