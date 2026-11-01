package dev.example.productai.agent;

import dev.example.productai.search.SearchResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一次 Agent 调查过程中累积的材料。
 *
 * <p>从 {@link AgentExecutor} 中独立出来，避免状态图执行器为了复用工具能力而依赖
 * 整个旧执行器的内部类型。</p>
 */
public class AgentHarvest {
    final Set<String> searchedProjects = new LinkedHashSet<>();
    final Map<String, SearchResult> codeHits = new LinkedHashMap<>();
    final Map<String, String> docHits = new LinkedHashMap<>();
    final Set<String> readFiles = new LinkedHashSet<>();
    final List<String> materials = new ArrayList<>();
    List<String> specifiedProjects = List.of();
}
