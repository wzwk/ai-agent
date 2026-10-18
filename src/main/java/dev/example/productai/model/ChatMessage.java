package dev.example.productai.model;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 单轮对话消息（user / assistant）
 
 * @author wengzewei*/
@Data
@AllArgsConstructor
public class ChatMessage {
    /** "user" 或 "assistant" */
    private String role;
    /** 消息内容（user 是原始问题，assistant 是回答正文，不含置信度尾标） */
    private String content;
}
