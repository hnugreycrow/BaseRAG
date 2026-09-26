package com.hnu.backend.rag.generation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** 从应用 classpath 统一加载 UTF-8 提示词资源。 */
final class PromptResourceLoader {
  /** 工具类不允许实例化。 */
  private PromptResourceLoader() {}

  /**
   * 读取并校验提示词资源。调用方通常把结果保存在静态常量中，因此每个资源只在类初始化时读取一次。
   *
   * @param resourcePath 相对于 classpath 根目录的资源路径
   * @return 保留 Markdown 格式和换行的完整提示词
   * @throws IllegalStateException 资源不存在、无法读取或内容为空时抛出
   */
  static String load(String resourcePath) {
    ClassLoader classLoader = PromptResourceLoader.class.getClassLoader();
    try (InputStream input = classLoader.getResourceAsStream(resourcePath)) {
      if (input == null) {
        throw new IllegalStateException("Prompt resource does not exist: " + resourcePath);
      }
      String prompt = new String(input.readAllBytes(), StandardCharsets.UTF_8);
      if (prompt.isBlank()) {
        throw new IllegalStateException("Prompt resource is empty: " + resourcePath);
      }
      return prompt;
    } catch (IOException error) {
      throw new IllegalStateException("Failed to read prompt resource: " + resourcePath, error);
    }
  }
}
