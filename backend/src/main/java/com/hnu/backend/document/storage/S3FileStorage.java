package com.hnu.backend.document.storage;

import com.hnu.backend.configuration.RagProperties;
import com.hnu.backend.shared.error.ApiException;
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

  public S3FileStorage(RagProperties config) {
    this.config = config;
  }

  private synchronized S3Client readyClient() {
    var storage = config.getStorage();
    if (storage.getAccessKey().isBlank() || storage.getSecretKey().isBlank()) {
      throw ApiException.bad("STORAGE_NOT_CONFIGURED", "请配置 RustFS 访问凭据");
    }
    if (client == null)
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
    if (!bucketReady) {
      try {
        client.headBucket(b -> b.bucket(storage.getBucket()));
      } catch (S3Exception e) {
        if (e.statusCode() != 404) throw e;
        try {
          client.createBucket(b -> b.bucket(storage.getBucket()));
        } catch (BucketAlreadyOwnedByYouException ignored) {
          /* another request initialized it */
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
      throw ApiException.upstream("STORAGE_UNAVAILABLE", "无法保存原文件，请检查 RustFS 服务");
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
      throw ApiException.upstream("STORAGE_UNAVAILABLE", "无法读取原文件，请检查 RustFS 服务");
    }
  }

  @Override
  public void remove(String key) {
    try {
      readyClient().deleteObject(b -> b.bucket(config.getStorage().getBucket()).key(key));
    } catch (ApiException e) {
      throw e;
    } catch (RuntimeException e) {
      throw ApiException.upstream("STORAGE_UNAVAILABLE", "无法删除原文件，请检查 RustFS 服务");
    }
  }

  @PreDestroy
  public synchronized void close() {
    if (client != null) client.close();
  }
}
