package com.hnu.backend.model.client;

import java.util.Arrays;

/** 与模型供应商无关的向量文本编码。 */
public final class EmbeddingVector {
  private EmbeddingVector() {}

  /** 将按维度排列的向量编码为 pgvector 字面量，保持原有浮点表示。 */
  public static String literal(float[] vector) {
    return Arrays.toString(vector);
  }
}
