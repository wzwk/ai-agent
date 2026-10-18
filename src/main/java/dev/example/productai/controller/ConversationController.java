package dev.example.productai.controller;

import dev.example.productai.repository.PersistenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 会话历史接口（阶段一持久化）：
 * - GET  /api/conversations                 最近会话列表（侧栏）
 * - GET  /api/conversations/{id}/messages   会话全部消息含智能体轨迹（刷新/回放恢复）
 * - DELETE /api/conversations/{id}          软删
 *
 * 鉴权跟随全局 AuthFilter（/api/** 统一令牌）。
 *
 * @author wengzewei
 */
@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final PersistenceService persistence;

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "30") int limit) {
        return persistence.listConversations(Math.min(Math.max(limit, 1), 100));
    }

    @GetMapping("/{id}/messages")
    public List<Map<String, Object>> messages(@PathVariable String id) {
        return persistence.findMessages(id);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        boolean ok = persistence.softDelete(id);
        return Map.of("ok", ok);
    }
}
