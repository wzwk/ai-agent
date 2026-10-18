package dev.example.productai.wecom;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 企微配置（PoC 阶段默认不启用）
 * 启用后用于：
 * - 接收企微回调消息（POST /api/wecom/callback）
 * - 发送文本/@消息到企微群
 
 * @author wengzewei*/
@Data
@ConfigurationProperties(prefix = "wecom")
public class WecomProperties {
    /** 是否启用企微集成 */
    private boolean enabled = false;
    private String corpId;
    private String agentId;
    private String secret;
    /** 回调 Token（企微后台设置） */
    private String callbackToken;
    /** 回调 EncodingAESKey */
    private String callbackAesKey;
}
