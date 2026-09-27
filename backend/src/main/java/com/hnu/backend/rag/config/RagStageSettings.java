package com.hnu.backend.rag.config;

/** 各阶段可见的最小配置视图；配置读取集中在装配层。 */
public final class RagStageSettings {
  private RagStageSettings() {}

  /** Planning 阶段配置契约。 */
  public interface Planning {
    /** 返回最大子问题数。 */
    int maxSubQuestions();

    /** 返回问题最大字符数。 */
    int maxQuestionChars();

    /** 返回规划保留的最近轮数。 */
    int recentTurns();
  }

  /** 从现有配置提供 Planning 视图，不更改键、默认值或读取时机。 */
  public static Planning planning(RagProperties config) {
    return new Planning() {
      @Override
      public int maxSubQuestions() {
        return config.getPipeline().getMaxSubQuestions();
      }

      @Override
      public int maxQuestionChars() {
        return config.getMaxQuestionChars();
      }

      @Override
      public int recentTurns() {
        return config.getPipeline().getPlanning().getRecentTurns();
      }
    };
  }

  /** Routing 阶段配置契约。 */
  public interface Routing {
    /** 返回超时毫秒数。 */
    int timeoutMs();

    /** 返回路由置信度阈值。 */
    double confidenceThreshold();
  }

  /** 从现有配置提供 Routing 视图，不更改键、默认值或读取时机。 */
  public static Routing routing(RagProperties config) {
    return new Routing() {
      @Override
      public int timeoutMs() {
        return config.getPipeline().getRouting().getTimeoutMs();
      }

      @Override
      public double confidenceThreshold() {
        return config.getPipeline().getRouting().getConfidenceThreshold();
      }
    };
  }

  /** Tools 阶段配置契约。 */
  public interface Tools {
    /** 返回工具开关。 */
    boolean enabled();

    /** 返回允许调用的工具名称。 */
    java.util.List<String> allowList();

    /** 返回超时毫秒数。 */
    int timeoutMs();

    /** 返回工具输出最大字符数。 */
    int maxOutputChars();
  }

  /** 从现有配置提供 Tools 视图，不更改键、默认值或读取时机。 */
  public static Tools tools(RagProperties config) {
    return new Tools() {
      @Override
      public boolean enabled() {
        return config.getPipeline().getMcp().isEnabled();
      }

      @Override
      public java.util.List<String> allowList() {
        return java.util.List.copyOf(config.getPipeline().getMcp().getAllowList());
      }

      @Override
      public int timeoutMs() {
        return config.getPipeline().getMcp().getTimeoutMs();
      }

      @Override
      public int maxOutputChars() {
        return config.getPipeline().getMcp().getMaxOutputChars();
      }
    };
  }

  /** Retrieval 阶段配置契约。 */
  public interface Retrieval {
    /** 返回当前不可变预算快照。 */
    com.hnu.backend.rag.pipeline.RagBudgetSnapshot budget();
  }

  /** 从现有配置提供 Retrieval 视图，不更改键、默认值或读取时机。 */
  public static Retrieval retrieval(RagProperties config) {
    return new Retrieval() {
      @Override
      public com.hnu.backend.rag.pipeline.RagBudgetSnapshot budget() {
        return com.hnu.backend.rag.pipeline.RagBudgetSnapshot.from(config);
      }
    };
  }

  /** Execution 阶段配置契约。 */
  public interface Execution {
    /** 返回当前不可变预算快照。 */
    com.hnu.backend.rag.pipeline.RagBudgetSnapshot budget();

    /** 返回子问题最大并发数。 */
    int parallelism();

    /** 返回工具排队与执行总预算毫秒数。 */
    int toolTimeoutMs();
  }

  /** 从现有配置提供 Execution 视图，不更改键、默认值或读取时机。 */
  public static Execution execution(RagProperties config) {
    return new Execution() {
      @Override
      public com.hnu.backend.rag.pipeline.RagBudgetSnapshot budget() {
        return com.hnu.backend.rag.pipeline.RagBudgetSnapshot.from(config);
      }

      @Override
      public int parallelism() {
        return config.getPipeline().getMaxSubQuestions();
      }

      @Override
      public int toolTimeoutMs() {
        return config.getPipeline().getMcp().getTimeoutMs();
      }
    };
  }
}
