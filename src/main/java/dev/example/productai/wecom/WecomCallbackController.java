package dev.example.productai.wecom;

import dev.example.productai.agent.ProductAgent;
import dev.example.productai.model.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

/**
 * 企微回调入口（PoC 占位）
 *
 * 接入步骤（当你打算接企微时）：
 * 1) 在企业微信管理后台 → 自建应用 → 接收消息 → 设置回调 URL
 *    URL 填: http(s)://<你的外网域名>/api/wecom/callback
 *    Token 和 EncodingAESKey 填到 application.yml 的 wecom.callback-token / wecom.callback-aes-key
 * 2) 实现签名验证 + AES 解密（参考腾讯官方 Java demo: WXBizMsgCrypt）
 * 3) 解析出 text 消息内容 → 调用 ProductAgent.ask() → 拼接回复 → 加密返回
 *
 * 当前 PoC 暂未实现加解密，先留接口形态。
 
 * @author wengzewei*/
@Slf4j
@RestController
@RequestMapping("/api/wecom")
@RequiredArgsConstructor
public class WecomCallbackController {

    private final WecomProperties props;

    @Autowired(required = false)
    private ProductAgent agent;

    @GetMapping("/callback")
    public String verify(@RequestParam("msg_signature") String sig,
                         @RequestParam("timestamp") String ts,
                         @RequestParam("nonce") String nonce,
                         @RequestParam("echostr") String echostr) {
        log.info("企微回调验证: sig={}, ts={}, nonce={}, echostr={}", sig, ts, nonce, echostr);
        // TODO: 校验签名后直接返回 echostr
        return echostr;
    }

    @PostMapping("/callback")
    public String receive(@RequestParam("msg_signature") String sig,
                          @RequestParam("timestamp") String ts,
                          @RequestParam("nonce") String nonce,
                          @RequestBody String encryptedXml) {
        log.info("收到企微消息: sig={}, ts={}, nonce={}", sig, ts, nonce);
        if (!props.isEnabled()) {
            log.warn("企微集成未启用，收到消息已忽略");
            return "success";
        }
        // TODO: 实际接入时
        // 1. WXBizMsgCrypt.decrypt(encryptedXml) → 明文 XML
        // 2. 解析出 Content / FromUserName / MsgType
        // 3. 调用 agent.ask(content) 得到 ChatResponse
        // 4. 如果 needHuman=true，调用 WecomMessageClient.send() 发文本到群并 @ 值班人
        // 5. 否则回复用户
        return "success";
    }
}
