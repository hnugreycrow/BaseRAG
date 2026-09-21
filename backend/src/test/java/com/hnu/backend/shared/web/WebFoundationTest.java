package com.hnu.backend.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hnu.backend.shared.error.ApiException;
import com.hnu.backend.shared.error.ErrorCode;
import com.hnu.backend.shared.error.GlobalExceptionHandler;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@ExtendWith(OutputCaptureExtension.class)
class WebFoundationTest {
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc =
        MockMvcBuilders.standaloneSetup(new TestController())
            .setControllerAdvice(new ApiResponseAdvice(), new GlobalExceptionHandler())
            .addFilters(new RequestIdFilter())
            .build();
  }

  @Test
  void wrapsSuccessfulJsonAndKeepsRequestIdConsistent() throws Exception {
    mvc.perform(get("/test/success"))
        .andExpect(status().isOk())
        .andExpect(header().exists(RequestIdFilter.REQUEST_ID_HEADER))
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.message").value("请求成功"))
        .andExpect(jsonPath("$.data.value").value("ok"))
        .andExpect(jsonPath("$.requestId").isNotEmpty());
  }

  @Test
  void mapsBusinessExceptionToTheUnifiedEnvelope() throws Exception {
    mvc.perform(get("/test/failure"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DOCUMENT_PROCESSING"))
        .andExpect(jsonPath("$.message").value("测试冲突"))
        .andExpect(jsonPath("$.data").doesNotExist())
        .andExpect(jsonPath("$.requestId").isNotEmpty());
  }

  @Test
  void keepsNoContentResponseEmpty() throws Exception {
    mvc.perform(delete("/test/resource"))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""));
  }

  @Test
  void logsSanitizedUnexpectedStackAndReturnsSafeEnvelope(CapturedOutput output) throws Exception {
    mvc.perform(get("/test/unexpected"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
        .andExpect(jsonPath("$.message").value("服务暂时不可用，请稍后重试"))
        .andExpect(jsonPath("$.requestId").isNotEmpty());

    assertThat(output).contains("code=INTERNAL_ERROR");
    assertThat(output).contains("exceptionType=IllegalStateException");
    assertThat(output).contains("WebFoundationTest");
    assertThat(output).doesNotContain("secret provider response");
  }

  @RestController
  @RequestMapping("/test")
  static class TestController {
    @GetMapping("/success")
    Map<String, String> success() {
      return Map.of("value", "ok");
    }

    @GetMapping("/failure")
    Map<String, String> failure() {
      throw ApiException.conflict(ErrorCode.DOCUMENT_PROCESSING, "测试冲突");
    }

    @GetMapping("/unexpected")
    Map<String, String> unexpected() {
      throw new IllegalStateException("secret provider response");
    }

    @DeleteMapping("/resource")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteResource() {}
  }
}
