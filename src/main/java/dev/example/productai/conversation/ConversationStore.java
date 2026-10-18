package dev.example.productai.conversation;

import dev.example.productai.model.ConversationSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存会话存储：sessionId → ConversationSession
 * PoC 阶段够用，进程重启即丢；要持久化再换 Redis/DB。
 *
 * 内置一个定时清理任务，把超过 TTL 没活动的会话干掉，避免内存泄漏。
 
 * @author wengzewei*/
@Slf4j
@Component
public class ConversationStore {

    /** 会话空闲 TTL：30 分钟 */
    public static final long SESSION_TTL_MS = 30 * 60 * 1000L;

    private final ConcurrentHashMap<String, ConversationSession> store = new ConcurrentHashMap<>();
    private final dev.example.productai.repository.PersistenceService persistence;

    public ConversationStore(dev.example.productai.repository.PersistenceService persistence) {
        this.persistence = persistence;
    }

    /** 有就返回，没有就新建一个；内存未命中时尝试从 DB 恢复（重启/TTL 清理后无缝续聊） */
    public ConversationSession getOrCreate(String sessionId) {
        String id = (sessionId == null || sessionId.isBlank())
                ? newSessionId()
                : sessionId.trim();
        ConversationSession s = store.computeIfAbsent(id, ConversationSession::new);
        s.touch();
        persistence.restoreIfAbsent(s);
        return s;
    }

    public String newSessionId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /** 定时清理：每 5 分钟扫一次，干掉超过 TTL 没活动的会话 */
    @Scheduled(fixedDelay = 5 * 60 * 1000L)
    public void evictIdle() {
        long now = System.currentTimeMillis();
        int before = store.size();
        store.entrySet().removeIf(e -> now - e.getValue().getLastActiveAtEpoch() > SESSION_TTL_MS);
        int removed = before - store.size();
        if (removed > 0) {
            log.debug("清理空闲会话: removed={}, remain={}", removed, store.size());
        }
    }
}
