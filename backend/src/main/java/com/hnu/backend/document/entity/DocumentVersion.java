package com.hnu.backend.document.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Data;

/** 文档的一次导入版本及其解析、向量化状态。 */
@Data
@TableName("document_versions")
public class DocumentVersion {
  /** 版本标识。 */
  @TableId(type = IdType.INPUT)
  private UUID id;

  /** 所属文档标识。 */
  private UUID documentId;

  /** 冗余保存的知识库标识，用于版本查询和清理。 */
  private UUID knowledgeBaseId;

  /** 用于内容去重的文件哈希。 */
  private String fileHash;

  /** 原始文件在对象存储中的键。 */
  private String storageKey;

  /** 导入处理状态。 */
  private String status;

  /** 导入失败时的稳定错误码。 */
  private String errorCode;

  /** 生成该版本内容的解析器版本。 */
  private String parserVersion;

  /** 生成该版本分块的切分器版本。 */
  private String chunkerVersion;

  /** 生成分块向量所用的模型名称。 */
  private String embeddingModel;

  /** 生成分块向量的维度。 */
  private Integer embeddingDimensions;

  /** 创建时间。 */
  private OffsetDateTime createdAt;
}
