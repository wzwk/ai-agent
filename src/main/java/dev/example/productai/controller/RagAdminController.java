package dev.example.productai.controller;

import dev.example.productai.config.ProjectsConfigLoader;
import dev.example.productai.rag.EmbeddingClient;
import dev.example.productai.rag.RagIndexJobService;
import dev.example.productai.rag.VectorSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 管理接口：
 * - POST /api/admin/rag/reindex?projects=a,b  增量索引（projects 缺省 = 全部本地可用项目）
 * - GET  /api/admin/rag/status                各项目索引进度
 * - GET  /api/admin/rag/search?q=..&k=8       手动试搜（调试召回）
 * 索引任务异步执行；同一进程同时只允许一个任务运行，进度通过 status 查询。
 *
 * @author wengzewei
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/rag")
@RequiredArgsConstructor
public class RagAdminController {

    private final RagIndexJobService indexJobs;
    private final VectorSearchService searcher;
    private final EmbeddingClient embedding;
    private final ProjectsConfigLoader loader;

    @PostMapping("/reindex")
    public Map<String, Object> reindex(@RequestParam(required = false) List<String> projects) {
        List<String> targets = (projects == null || projects.isEmpty())
                ? allLocalProjects() : projects;
        if (targets.isEmpty()) return Map.of("ok", false, "msg", "没有可索引的本地项目");
        log.info("手动触发 RAG 索引: {}", targets);
        RagIndexJobService.StartResult result = indexJobs.start(targets);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", result.accepted());
        out.put("accepted", result.accepted());
        out.put("msg", result.message());
        out.put("jobId", result.job().jobId());
        out.put("state", result.job().state());
        out.put("projects", result.job().projects());
        return out;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ollamaUp", embedding.ping());
        out.put("job", indexJobs.status());
        out.put("projects", searcher.status());
        return out;
    }

    @GetMapping("/reindex/status")
    public RagIndexJobService.JobSnapshot reindexStatus() {
        return indexJobs.status();
    }

    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam String q,
                                      @RequestParam(required = false) List<String> projects,
                                      @RequestParam(defaultValue = "8") int k) {
        try {
            List<VectorSearchService.Hit> hits = searcher.search(q, projects, Math.min(Math.max(k, 1), 20));
            List<Map<String, Object>> list = new ArrayList<>();
            for (VectorSearchService.Hit h : hits) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("file", h.filePath());
                m.put("lines", h.startLine() + "-" + h.endLine());
                m.put("score", String.format("%.4f", h.score()));
                m.put("context", h.contextHeader());
                m.put("snippet", h.snippet() == null ? "" : h.snippet().substring(0, Math.min(300, h.snippet().length())));
                list.add(m);
            }
            return Map.of("ok", true, "q", q, "hits", list);
        } catch (Exception e) {
            return Map.of("ok", false, "msg", String.valueOf(e.getMessage()));
        }
    }

    private List<String> allLocalProjects() {
        List<String> out = new ArrayList<>();
        loader.getProjects().forEach(p -> out.add(p.getName()));
        return out;
    }
}
