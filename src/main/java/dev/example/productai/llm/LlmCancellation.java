package dev.example.productai.llm;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LLM 生成取消协调器
 * - REGISTRY：requestId -> 取消标记，供 /api/chat/cancel 翻牌
 * - TOKEN：请求处理线程绑定的取消标记，LLM 客户端在流式读取中逐块检查
 *
 * @author wengzewei
 */
public final class LlmCancellation {

    private static final Map<String, AtomicBoolean> REGISTRY = new ConcurrentHashMap<>();
    private static final ThreadLocal<AtomicBoolean> TOKEN = new ThreadLocal<>();

    private LlmCancellation() {
    }

    /** 注册一个可取消的请求，返回取消标记 */
    public static AtomicBoolean register(String requestId) {
        AtomicBoolean flag = new AtomicBoolean(false);
        REGISTRY.put(requestId, flag);
        return flag;
    }

    /** @return true=该 requestId 存在且已置为取消 */
    public static boolean cancel(String requestId) {
        AtomicBoolean flag = REGISTRY.get(requestId);
        if (flag == null) {
            return false;
        }
        flag.set(true);
        return true;
    }

    public static void unregister(String requestId) {
        REGISTRY.remove(requestId);
    }

    /** 在当前线程绑定取消标记；处理结束后必须 unbind */
    public static void bind(AtomicBoolean flag) {
        TOKEN.set(flag);
    }

    public static void unbind() {
        TOKEN.remove();
    }

    /** 当前线程的取消标记；未绑定时返回 null（不可取消场景） */
    public static AtomicBoolean token() {
        return TOKEN.get();
    }

    public static boolean isCancelled() {
        AtomicBoolean t = TOKEN.get();
        return t != null && t.get();
    }
}
