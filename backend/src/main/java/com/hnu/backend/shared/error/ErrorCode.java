package com.hnu.backend.shared.error;

import java.util.Arrays;
import java.util.Optional;
import org.springframework.http.HttpStatus;

/**
 * 稳定错误码目录。
 *
 * <p>枚举名即对外暴露和持久化的错误码；已发布后不得重命名。原因码、状态码和路由决策码不属于此目录。
 */
public enum ErrorCode {
  ACCOUNT_DISABLED("账号已被禁用", HttpStatus.FORBIDDEN),
  AUTH_REQUIRED("请先登录", HttpStatus.UNAUTHORIZED),
  AUTH_STORE_UNAVAILABLE("认证服务暂时不可用，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE),
  CHUNK_NOT_FOUND("分块不存在", HttpStatus.NOT_FOUND),
  CITED_SOURCE_NOT_FOUND("引用来源不存在", HttpStatus.NOT_FOUND),
  CONVERSATION_NOT_FOUND("会话不存在", HttpStatus.NOT_FOUND),
  CSRF_INVALID("CSRF Token 无效，请刷新页面后重试", HttpStatus.FORBIDDEN),
  DATABASE_RETRIEVAL_FAILED("向量检索失败", HttpStatus.BAD_GATEWAY),
  DOCUMENT_NOT_FOUND("文档不存在", HttpStatus.NOT_FOUND),
  DOCUMENT_PROCESSING("文档正在分块，请稍后刷新", HttpStatus.CONFLICT),
  DOCUMENT_VERSION_NOT_FOUND("文档版本不存在", HttpStatus.NOT_FOUND),
  DOCX_ZIP_LIMIT("DOCX 解压内容超出安全限制", HttpStatus.BAD_REQUEST),
  EMBEDDING_BINDING_CHANGED("向量模型配置已变更，请恢复原配置", HttpStatus.CONFLICT),
  EMBEDDING_FAILED("Embedding 请求失败", HttpStatus.BAD_GATEWAY),
  EMBEDDING_INVALID_RESPONSE("Embedding 返回的结果无效", HttpStatus.BAD_GATEWAY),
  EMBEDDING_MODEL_CHANGED("Embedding 供应商、模型或维度已变更，请恢复原配置", HttpStatus.CONFLICT),
  EMBEDDING_MODEL_UNAVAILABLE("知识库绑定的向量模型不可用", HttpStatus.CONFLICT),
  EMPTY_DOCUMENT("文档没有可用文本", HttpStatus.BAD_REQUEST),
  ENCRYPTED_PDF("不支持加密 PDF", HttpStatus.BAD_REQUEST),
  EXECUTION_FAILED("子问题执行失败", HttpStatus.BAD_GATEWAY),
  FILE_TOO_LARGE("文件超过格式大小限制", HttpStatus.CONTENT_TOO_LARGE),
  FORBIDDEN("当前账号无权执行此操作", HttpStatus.FORBIDDEN),
  GENERATION_CANCELLED("生成已停止", HttpStatus.CONFLICT),
  GENERATION_FAILED("模型未完整生成有效回答，请重试", HttpStatus.BAD_GATEWAY),
  GENERATION_IN_PROGRESS("该会话正在生成回答", HttpStatus.CONFLICT),
  GENERATION_INTERRUPTED("应用重启中断了生成，请重试", HttpStatus.INTERNAL_SERVER_ERROR),
  GENERATION_NOT_FOUND("生成任务不存在", HttpStatus.NOT_FOUND),
  IMPORT_BUSY("正在处理其他文档，请稍后重试", HttpStatus.TOO_MANY_REQUESTS),
  IMPORT_FAILED("文档入库失败，请检查服务状态后重试", HttpStatus.BAD_GATEWAY),
  IMPORT_INTERRUPTED("应用重启中断了文档处理，请重试", HttpStatus.INTERNAL_SERVER_ERROR),
  INTENT_CYCLE("意图树不能成环", HttpStatus.BAD_REQUEST),
  INTENT_DEPTH_EXCEEDED("意图树最多三级", HttpStatus.BAD_REQUEST),
  INTENT_LEAF_LIMIT("启用的叶子节点最多 32 个", HttpStatus.BAD_REQUEST),
  INTENT_NODE_HAS_CHILDREN("请先删除子节点", HttpStatus.CONFLICT),
  INTENT_NODE_NOT_FOUND("意图节点不存在", HttpStatus.NOT_FOUND),
  INTERNAL_ERROR("服务暂时不可用，请稍后重试", HttpStatus.INTERNAL_SERVER_ERROR),
  INVALID_BATCH("请选择 1 到 50 篇不同的文档", HttpStatus.BAD_REQUEST),
  INVALID_BATCH_SIZE("每次请选择 1 至 10 个文件", HttpStatus.BAD_REQUEST),
  INVALID_CITATIONS("模型连续返回无效引用，请重试", HttpStatus.BAD_GATEWAY),
  INVALID_CONVERSATION_TITLE("会话标题应为 1 到 200 个有效字符", HttpStatus.BAD_REQUEST),
  INVALID_CREDENTIALS("用户名或密码错误", HttpStatus.UNAUTHORIZED),
  INVALID_DISPLAY_NAME("显示名应为 1 到 100 个有效字符", HttpStatus.BAD_REQUEST),
  INVALID_DOCUMENT_NAME("文档名称应为 1 到 255 个有效字符", HttpStatus.BAD_REQUEST),
  INVALID_DOCX("DOCX 文件无法解析", HttpStatus.BAD_REQUEST),
  INVALID_EMBEDDING_MODEL("请选择配置文件中可用的向量模型", HttpStatus.BAD_REQUEST),
  INVALID_FILE("文件无效", HttpStatus.BAD_REQUEST),
  INVALID_FILE_FORMAT("文件内容与扩展名不符", HttpStatus.BAD_REQUEST),
  INVALID_INTENT_BINDING("意图节点绑定无效", HttpStatus.BAD_REQUEST),
  INVALID_INTENT_KIND("意图节点类型无效", HttpStatus.BAD_REQUEST),
  INVALID_INTENT_NODE("意图节点无效", HttpStatus.BAD_REQUEST),
  INVALID_INTENT_PARENT("父节点不存在", HttpStatus.BAD_REQUEST),
  INVALID_KNOWLEDGE_BASE_NAME("知识库名称应为 1 到 200 个有效字符", HttpStatus.BAD_REQUEST),
  INVALID_PAGE("页码应大于 0，每页数量应为 1 到 100", HttpStatus.BAD_REQUEST),
  INVALID_PASSWORD("密码至少 12 个字符且 UTF-8 编码不能超过 72 字节", HttpStatus.BAD_REQUEST),
  INVALID_PDF("PDF 文件无法解析", HttpStatus.BAD_REQUEST),
  INVALID_QUESTION("请输入有效问题", HttpStatus.BAD_REQUEST, false),
  INVALID_REQUEST("请检查请求参数及文件", HttpStatus.BAD_REQUEST),
  INVALID_TIME_RANGE("开始时间必须早于结束时间", HttpStatus.BAD_REQUEST),
  INVALID_USERNAME("用户名应为 3 到 64 位 ASCII 字母、数字或 . _ -", HttpStatus.BAD_REQUEST),
  INVALID_UTF8("请使用 UTF-8 编码的 Markdown 文件", HttpStatus.BAD_REQUEST),
  KNOWLEDGE_BASE_NOT_FOUND("知识库不存在", HttpStatus.NOT_FOUND),
  LAST_ADMIN_REQUIRED("不能禁用最后一个启用的管理员", HttpStatus.CONFLICT),
  LOGIN_RATE_LIMITED("登录失败次数过多，请 15 分钟后重试", HttpStatus.TOO_MANY_REQUESTS),
  MEMORY_LOAD_FAILED("会话记忆加载失败", HttpStatus.INTERNAL_SERVER_ERROR),
  MESSAGE_INCOMPLETE("消息尚未创建回答", HttpStatus.CONFLICT),
  MESSAGE_NOT_FOUND("回答不存在", HttpStatus.NOT_FOUND),
  METHOD_NOT_ALLOWED("请求方法不受支持", HttpStatus.METHOD_NOT_ALLOWED),
  MODEL_CIRCUIT_OPEN("模型服务暂时不可用，请稍后重试", HttpStatus.BAD_GATEWAY),
  MODEL_HTTP_ERROR("模型服务请求失败", HttpStatus.BAD_GATEWAY),
  MODEL_INVALID_RESPONSE("模型服务返回了无效结果", HttpStatus.BAD_GATEWAY),
  MODEL_NOT_CONFIGURED("模型未配置 API Key", HttpStatus.BAD_REQUEST),
  MODEL_RATE_LIMITED("模型服务请求过于频繁，请稍后重试", HttpStatus.BAD_GATEWAY),
  MODEL_TIMEOUT("模型请求超时，请稍后重试", HttpStatus.BAD_GATEWAY),
  MODEL_UNAVAILABLE("无法连接模型服务", HttpStatus.BAD_GATEWAY),
  PDF_NO_TEXT("PDF 没有可提取文本", HttpStatus.BAD_REQUEST),
  RAG_RUN_NOT_FOUND("问答运行记录不存在", HttpStatus.NOT_FOUND),
  REGENERATE_NOT_ALLOWED("只能重新生成会话最后一轮的当前成功回答", HttpStatus.CONFLICT),
  REQUEST_INTERRUPTED("请求已中断", HttpStatus.BAD_GATEWAY),
  RERANK_FAILED("重排模型请求失败", HttpStatus.BAD_GATEWAY),
  RERANK_INVALID_RESPONSE("重排模型返回了无效结果", HttpStatus.BAD_GATEWAY),
  RERANK_INVALID_RESULT("重排结果无效", HttpStatus.BAD_GATEWAY),
  RERANK_TIMEOUT("重排模型请求超时", HttpStatus.BAD_GATEWAY),
  RERANK_UNAVAILABLE("没有可用的重排模型", HttpStatus.BAD_GATEWAY),
  RESOURCE_NOT_FOUND("请求的资源不存在", HttpStatus.NOT_FOUND),
  RETRY_NOT_ALLOWED("只能重试失败或已停止的回答", HttpStatus.CONFLICT),
  RUN_TERMINATED("问答运行已终止", HttpStatus.INTERNAL_SERVER_ERROR),
  SELF_DISABLE_NOT_ALLOWED("不能禁用当前登录账号", HttpStatus.CONFLICT),
  STORAGE_NOT_CONFIGURED("请配置 RustFS 访问凭据", HttpStatus.BAD_REQUEST),
  STORAGE_UNAVAILABLE("文件存储服务不可用", HttpStatus.BAD_GATEWAY),
  SUBQUESTION_TIMEOUT("向量检索通道超时", HttpStatus.BAD_GATEWAY),
  TOO_MANY_CHUNKS("单份文档最多处理 1000 个片段，请拆分文档", HttpStatus.BAD_REQUEST),
  TOOL_FAILED("工具执行失败", HttpStatus.BAD_GATEWAY),
  TOOL_INTERRUPTED("工具执行已中断", HttpStatus.BAD_GATEWAY),
  TOOL_TIMEOUT("工具执行超时", HttpStatus.BAD_GATEWAY),
  TRACE_OR_RESULT_PERSISTENCE_FAILED("运行记录或结果保存失败", HttpStatus.INTERNAL_SERVER_ERROR),
  UNSUPPORTED_MEDIA_TYPE("请求内容类型不受支持", HttpStatus.UNSUPPORTED_MEDIA_TYPE),
  UPLOAD_REQUEST_TOO_LARGE("上传请求不能超过 60 MB", HttpStatus.CONTENT_TOO_LARGE),
  USER_NOT_FOUND("用户不存在", HttpStatus.NOT_FOUND),
  USERNAME_EXISTS("用户名已存在", HttpStatus.CONFLICT);

  private final String defaultMessage;
  private final HttpStatus status;
  private final boolean retryable;

  ErrorCode(String defaultMessage, HttpStatus status) {
    this(defaultMessage, status, true);
  }

  ErrorCode(String defaultMessage, HttpStatus status, boolean retryable) {
    this.defaultMessage = defaultMessage;
    this.status = status;
    this.retryable = retryable;
  }

  public String code() {
    return name();
  }

  public String defaultMessage() {
    return defaultMessage;
  }

  public HttpStatus status() {
    return status;
  }

  public boolean retryable() {
    return retryable;
  }

  /** 兼容读取数据库中已存在的错误码。 */
  public static Optional<ErrorCode> fromCode(String code) {
    if (code == null || code.isBlank()) {
      return Optional.empty();
    }
    return Arrays.stream(values()).filter(value -> value.code().equals(code)).findFirst();
  }

  /** 返回已知错误码的安全用户提示，未知历史码返回 {@code null}。 */
  public static String messageFor(String code) {
    return fromCode(code).map(ErrorCode::defaultMessage).orElse(null);
  }

  /** 判断已持久化错误是否允许重试；未知历史码默认允许。 */
  public static boolean retryable(String code) {
    return fromCode(code).map(ErrorCode::retryable).orElse(true);
  }
}
