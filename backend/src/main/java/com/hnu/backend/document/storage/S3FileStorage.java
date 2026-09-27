package com.hnu.backend.document.storage;

import com.hnu.backend.common.exception.ApiException;
import com.hnu.backend.common.exception.ErrorCode;
import com.hnu.backend.rag.config.RagProperties;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.time.Duration;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

/** 通过 S3 兼容接口在 RustFS 中保存文档原文件。 */
@Component
public class S3FileStorage implements FileStorage {
  private final RagProperties config;
  private S3Client client;
  private boolean bucketReady;

  /**
   * 创建延迟连接的原文件存储适配器；首次文件操作时才建立 S3 客户端。
   *
   * @param config 包含 RustFS 地址、桶和访问凭据的配置
   */
  public S3FileStorage(RagProperties config) {
    this.config = config;
  }

  private synchronized S3Client readyClient() {
    var storage = config.getStorage();
    if (storage.getAccessKey().isBlank() || storage.getSecretKey().isBlank()) {
      throw ApiException.bad(ErrorCode.STORAGE_NOT_CONFIGURED, "请配置 RustFS 访问凭据");
    }
    if (client == null) {
      client =
          S3Client.builder()
              .endpointOverride(URI.create(storage.getEndpoint()))
              .region(Region.of(storage.getRegion()))
              .credentialsProvider(
                  StaticCredentialsProvider.create(
                      AwsBasicCredentials.create(storage.getAccessKey(), storage.getSecretKey())))
              .forcePathStyle(true)
              .httpClientBuilder(
                  UrlConnectionHttpClient.builder()
                      .connectionTimeout(Duration.ofSeconds(5))
                      .socketTimeout(Duration.ofSeconds(30)))
              .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(60)))
              .build();
    }
    if (!bucketReady) {
      try {
        client.headBucket(b -> b.bucket(storage.getBucket()));
      } catch (S3Exception e) {
        if (e.statusCode() != 404) {
          throw e;
        }
        try {
          client.createBucket(b -> b.bucket(storage.getBucket()));
        } catch (BucketAlreadyOwnedByYouException ignored) {
          // 其他实例可能在检查后已创建同名桶，此时直接使用该桶。
        }
      }
      bucketReady = true;
    }
    return client;
  }

  /** 按服务端确认的 MIME 类型写入原文件，以便后续正确读取或预览。 */
  @Override
  public void put(String key, byte[] content, String mediaType) {
    try {
      readyClient()
          .putObject(
              b -> b.bucket(config.getStorage().getBucket()).key(key).contentType(mediaType),
              RequestBody.fromBytes(content));
    } catch (ApiException e) {
      throw e;
    } catch (RuntimeException e) {
      throw ApiException.upstream(ErrorCode.STORAGE_UNAVAILABLE, "无法保存原文件，请检查 RustFS 服务", e);
    }
  }

  @Override
  public byte[] get(String key) {
    try {
      return readyClient()
          .getObjectAsBytes(b -> b.bucket(config.getStorage().getBucket()).key(key))
          .asByteArray();
    } catch (ApiException e) {
      throw e;
    } catch (RuntimeException e) {
      throw ApiException.upstream(ErrorCode.STORAGE_UNAVAILABLE, "无法读取原文件，请检查 RustFS 服务", e);
    }
  }

  @Override
  public void remove(String key) {
    try {
      readyClient().deleteObject(b -> b.bucket(config.getStorage().getBucket()).key(key));
    } catch (ApiException e) {
      throw e;
    } catch (RuntimeException e) {
      throw ApiException.upstream(ErrorCode.STORAGE_UNAVAILABLE, "无法删除原文件，请检查 RustFS 服务", e);
    }
  }

  /** 应用停止时释放已建立的 S3 客户端连接。 */
  @PreDestroy
  public synchronized void close() {
    if (client != null) {
      client.close();
    }
  }
}
