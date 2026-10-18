package dev.example.productai.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.example.productai.config.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地 embedding 客户端（Ollama /api/embed，bge-m3）。
 * embedding 全程不出内网——这是数据安全边界的一部分。
 *
 * @author wengzewei
 */
@Slf4j
@Component
public class EmbeddingClient {

    private final RagProperties props;
    private final ObjectMapper mapper;
    private final RestClient embeddingClient;
    private final RestClient healthClient;

    public EmbeddingClient(RagProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.embeddingClient = buildClient(5_000, props.getEmbedding().getTimeoutMs());
        this.healthClient = buildClient(2_000, 3_000);
    }

    /** 批量 embed： texts 数量须 ≤ batchSize；返回与输入等长的向量列表 */
    public List<float[]> embed(List<String> texts) throws JsonProcessingException {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", props.getEmbedding().getModel());
        ArrayNode arr = body.putArray("input");
        texts.forEach(arr::add);

        String resp = embeddingClient.post()
                .uri(endpoint("/api/embed"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.toString())
                .retrieve()
                .body(String.class);

        List<float[]> out = new ArrayList<>();
        JsonNode embeddings = mapper.readTree(resp == null ? "{}" : resp).path("embeddings");
        if (!embeddings.isArray() || embeddings.size() != texts.size()) {
            throw new IllegalStateException("embedding 返回数量不符: " + embeddings.size() + " != " + texts.size());
        }
        for (JsonNode vec : embeddings) {
            float[] v = new float[vec.size()];
            for (int i = 0; i < vec.size(); i++) v[i] = (float) vec.get(i).asDouble();
            out.add(v);
        }
        return out;
    }

    /** 单条 embed（查询向量） */
    public float[] embedOne(String text) {
        try {
            return embed(List.of(text)).get(0);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("embedding 响应解析失败: " + e.getMessage(), e);
        }
    }

    /** 探活：Ollama 是否可用 */
    public boolean ping() {
        try {
            String resp = healthClient.get().uri(endpoint("/api/tags")).retrieve().body(String.class);
            return resp != null && resp.contains("models");
        } catch (Exception e) {
            log.warn("Ollama 探活失败 {}: {}", props.getEmbedding().getBaseUrl(), e.getMessage());
            return false;
        }
    }

    /** 向量转 PGvector 字面量：[0.1,0.2,...]（配合 ?::vector 使用） */
    public static String toPgVector(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }

    private String endpoint(String path) {
        String baseUrl = props.getEmbedding().getBaseUrl();
        return (baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl) + path;
    }

    private static RestClient buildClient(int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeoutMs);
        requestFactory.setReadTimeout(readTimeoutMs);
        return RestClient.builder().requestFactory(requestFactory).build();
    }
}
