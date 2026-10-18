package dev.example.productai.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 用户建议反馈（v0.11 自动调优闭环的入口）：
 * 使用者把遇到的问题/想法写进来落库；每日计划任务跑无头 Claude 审阅，
 * 好建议直接实现并部署，处理结果回写 status/admin_note。
 *
 * @author wengzewei
 */
@Slf4j
@RestController
@RequestMapping("/api/feedback")
@RequiredArgsConstructor
public class FeedbackController {

    private final JdbcTemplate jdbc;

    /** 提交建议（前端弹窗调用；content 必填，联系方式/会话号选填便于追问） */
    @PostMapping
    public ResponseEntity<?> submit(@RequestBody Map<String, String> body) {
        String content = body.getOrDefault("content", "").trim();
        if (content.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "内容不能为空"));
        }
        String contact = trimTo(body.get("contact"), 100);
        String sessionId = trimTo(body.get("sessionId"), 64);
        Long id = jdbc.queryForObject(
                "INSERT INTO feedback (content, contact, session_id) VALUES (?, ?, ?) RETURNING id",
                Long.class, content, contact, sessionId);
        log.info("收到建议反馈 #{}：{}", id, content.length() > 80 ? content.substring(0, 80) + "..." : content);
        return ResponseEntity.ok(Map.of("ok", true, "id", id));
    }

    /** 建议列表（?status=pending 只看待处理；自动化脚本与前端查看用） */
    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "") String status) {
        String sql = """
                SELECT id, content, contact, session_id, status, admin_note,
                       to_char(created_at, 'MM-DD HH24:MI') AS created_at,
                       to_char(handled_at, 'MM-DD HH24:MI') AS handled_at
                FROM feedback
                """;
        if (!status.isBlank()) {
            return jdbc.queryForList(sql + " WHERE status = ? ORDER BY id", status);
        }
        return jdbc.queryForList(sql + " ORDER BY id DESC LIMIT 100");
    }

    /** 回写处理结果（自动化脚本调用）：status + 备注，一次一条 */
    @PostMapping("/{id}/status")
    public Map<String, Object> updateStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String status = body.getOrDefault("status", "").trim();
        List<String> allowed = List.of("pending", "accepted", "done", "rejected");
        if (!allowed.contains(status)) {
            return Map.of("ok", false, "error", "status 必须是 " + allowed);
        }
        String note = trimTo(body.get("note"), 2000);
        int n = jdbc.update("UPDATE feedback SET status = ?, admin_note = ?, handled_at = now() WHERE id = ?",
                status, note, id);
        return Map.of("ok", n > 0);
    }

    private String trimTo(String s, int max) {
        if (s == null) return null;
        s = s.trim();
        return s.isEmpty() ? null : (s.length() > max ? s.substring(0, max) : s);
    }
}
