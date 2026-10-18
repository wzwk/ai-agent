package dev.example.productai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 配置（阶段二：本地向量检索，embedding 不出内网）。
 *
 * @author wengzewei
 */
@Data
@ConfigurationProperties(prefix = "app.rag")
public class RagProperties {

    /** 总开关：Ollama 未就绪时工具返回结构化错误，agent 自动退回 search_code */
    private boolean enabled = true;

    private Embedding embedding = new Embedding();
    private Index index = new Index();

    @Data
    public static class Embedding {
        /** Ollama 本地服务地址 */
        private String baseUrl = "http://localhost:11434";
        private String model = "bge-m3";
        /** bge-m3 输出 1024 维；换模型必须同步改 rag_chunks.embedding 的 vector(N) 并全量重建 */
        private int dimension = 1024;
        private int batchSize = 8;
        /** 单次 embed 请求超时（毫秒） */
        private int timeoutMs = 300000;
    }

    @Data
    public static class Index {
        /** 单个切片最大字符数 */
        private int maxChars = 1800;
        /** 单个切片最大行数 */
        private int chunkLines = 70;
        /** 跳过超过该大小的源文件（字节），防止超大生成文件污染索引 */
        private long maxFileBytes = 300_000;
    }
}
