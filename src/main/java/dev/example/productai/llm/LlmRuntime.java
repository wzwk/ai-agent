package dev.example.productai.llm;

import lombok.Builder;
import lombok.Data;

/**
 * 单次请求的 LLM 运行时覆盖参数（访客自带 Key 场景）。
 * apiKey 只在当次请求内使用：不落库、不进日志。
 
 * @author wengzewei*/
@Data
@Builder
public class LlmRuntime {
    /** OpenAI 兼容端点，如 https://api.deepseek.com/v1 */
    private String baseUrl;
    /** 访客自己的 API Key */
    private String apiKey;
    /** 模型名，如 deepseek-chat / glm-4 / glm-4-flash */
    private String model;

    public boolean hasKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
