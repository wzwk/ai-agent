package dev.example.productai.model;

import lombok.Data;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一次会话：保存最近若干轮 Q&A，用于多轮上下文。
 * 不持久化，进程重启即丢失（PoC 阶段够用）。
 
 * @author wengzewei*/
@Data
public class ConversationSession {

    /** 单个会话最多保留多少轮（一轮 = 一问一答） */
    public static final int MAX_TURNS = 6;

    private final String sessionId;
    private final long createdAtEpoch;
    private volatile long lastActiveAtEpoch;
    /** 只保留最近 MAX_TURNS*2 条消息（user+assistant 算两条） */
    private final Deque<ChatMessage> history = new ArrayDeque<>();
    /** 本会话已用共享 Key 提问的次数（免费额度计数） */
    private final AtomicInteger askedCount = new AtomicInteger();

    public ConversationSession(String sessionId) {
        this.sessionId = sessionId;
        this.createdAtEpoch = System.currentTimeMillis();
        this.lastActiveAtEpoch = this.createdAtEpoch;
    }

    public void touch() {
        this.lastActiveAtEpoch = System.currentTimeMillis();
    }

    /** 追加一条消息，超限时丢弃最旧的 */
    public synchronized void append(ChatMessage msg) {
        history.addLast(msg);
        int limit = MAX_TURNS * 2;
        while (history.size() > limit) {
            history.pollFirst();
        }
        touch();
    }

    /** 返回按时间正序的消息列表（最旧在前） */
    public synchronized List<ChatMessage> snapshot() {
        return new ArrayList<>(history);
    }

    /** 最终答复追加范围说明后，同步修正会话中刚写入的成文内容。 */
    public synchronized void replaceLastAssistantAnswer(String original, String replacement) {
        ChatMessage last = history.peekLast();
        if (last != null && "assistant".equals(last.getRole())
                && java.util.Objects.equals(original, last.getContent())) {
            last.setContent(replacement);
            touch();
        }
    }

    /** 共享 Key 提问次数 +1，返回累加后的值 */
    public int incrAskedCount() {
        return askedCount.incrementAndGet();
    }

    /** 从 DB 恢复（重启/清缓存后）：仅当内存为空时生效，幂等 */
    public synchronized void restoreFrom(List<ChatMessage> msgs, int askedCount) {
        if (!history.isEmpty()) return;
        history.addAll(msgs);
        this.askedCount.set(askedCount);
    }

    /** 已用共享 Key 提问的次数 */
    public int getAskedCount() {
        return askedCount.get();
    }
}
