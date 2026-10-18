package dev.example.productai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

@Data
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {
    /** 当前激活的 provider：deepseek / zhipu 
 * @author wengzewei*/
    private String active = "deepseek";
    private Map<String, Provider> providers;

    @Data
    public static class Provider {
        private String baseUrl;
        private String apiKey;
        private String model;
        /**
         * 智能体决策轮专用快模型（选工具不需要深度推理）：
         * 空 = 决策轮与成文轮同模型。BYOK 时不生效（尊重访客自选模型）。
         * 例：zhipu 主模型 glm-5.2（推理强但慢），decision-model 配 glm-5.3-flash。
         */
        private String decisionModel;
        private Double temperature = 0.3;
        private Integer maxTokens = 4096;
    }

    /**
     * 注意：不要命名为 getActive()，会和 Lombok 为 active 字段生成的 getActive() 冲突
     */
    public Provider getActiveProvider() {
        if (providers == null || active == null) {
            throw new IllegalStateException("LLM providers 未配置");
        }
        Provider p = providers.get(active);
        if (p == null) {
            throw new IllegalStateException("未找到激活的 LLM provider: " + active);
        }
        return p;
    }
}
