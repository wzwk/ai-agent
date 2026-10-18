package dev.example.productai.wecom;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 企微主动发消息客户端（PoC 占位）
 *
 * 实际接入时使用以下接口：
 * - 获取 access_token: POST https://qyapi.weixin.qq.com/cgi-bin/gettoken?corpid=ID&corpsecret=SECRET
 * - 发送应用消息: POST https://qyapi.weixin.qq.com/cgi-bin/message/send?access_token=XXX
 *   body 示例:
 *   {
 *     "touser": "UserID1|UserID2",
 *     "msgtype": "text",
 *     "agentid": 1000002,
 *     "text": { "content": "你的消息内容" },
 *     "mentioned_list": ["userid1", "@all"]   // 群里 @ 人用
 *   }
 
 * @author wengzewei*/
@Slf4j
@Component
@RequiredArgsConstructor
public class WecomMessageClient {

    private final WecomProperties props;

    /** 发送文本消息到指定用户/群 */
    public void sendText(String toUser, String content) {
        if (!props.isEnabled()) {
            log.info("[企微未启用] 假装发送给 {} : {}", toUser, content);
            return;
        }
        // TODO: 实际实现
        // 1. 获取/缓存 access_token
        // 2. POST /cgi-bin/message/send
        log.info("发送企微消息给 {}: {}", toUser, content);
    }

    /** 发送文本到群，并 @ 指定人 */
    public void sendTextWithMention(String chatId, String content, java.util.List<String> mentionUserIds) {
        if (!props.isEnabled()) {
            log.info("[企微未启用] 假装发送到群 {} 并 @ {}: {}", chatId, mentionUserIds, content);
            return;
        }
        // TODO: 实际实现，群消息接口路径不同：
        // POST https://qyapi.weixin.qq.com/cgi-bin/appchat/send?access_token=XXX
        log.info("发送群消息 chatId={}, mention={}, content={}", chatId, mentionUserIds, content);
    }
}
