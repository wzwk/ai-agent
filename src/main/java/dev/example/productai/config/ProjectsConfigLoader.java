package dev.example.productai.config;

import dev.example.productai.model.FeatureInfo;
import dev.example.productai.model.ProjectInfo;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 加载 projects.yml 配置，启动时读取一次，缓存到内存
 
 * @author wengzewei*/
@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectsConfigLoader {

    private final ProjectsProperties props;
    private final ResourceLoader resourceLoader;

    private List<ProjectInfo> projects = Collections.emptyList();

    @PostConstruct
    public void load() {
        String location = props.getConfigLocation();
        log.info("加载项目元数据: {}", location);
        try {
            Resource resource = resourceLoader.getResource(location);
            try (InputStream in = resource.getInputStream()) {
                Yaml yaml = new Yaml();
                Map<String, Object> root = yaml.load(in);
                Object projectsObj = root.get("projects");
                if (!(projectsObj instanceof List<?> list)) {
                    log.warn("projects.yml 中未找到 projects 列表");
                    return;
                }
                List<ProjectInfo> result = new ArrayList<>(list.size());
                for (Object item : list) {
                    if (item instanceof Map<?, ?> m) {
                        result.add(parseProject(m));
                    }
                }
                this.projects = Collections.unmodifiableList(result);
                log.info("加载完成，共 {} 个项目", projects.size());
                projects.forEach(p -> log.debug("  - {} : {}", p.getName(), p.getDescription()));
            }
        } catch (Exception e) {
            log.error("加载 projects.yml 失败: {}", location, e);
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private ProjectInfo parseProject(Map<?, ?> m) {
        ProjectInfo.ProjectInfoBuilder b = ProjectInfo.builder();
        b.name((String) m.get("name"));
        b.path((String) m.get("path"));
        b.description((String) m.get("description"));
        b.priority((String) m.get("priority"));
        Object tags = m.get("tags");
        if (tags instanceof List<?> l) b.tags((List<String>) l);
        Object rpcTo = m.get("rpc_to");
        if (rpcTo instanceof List<?> l) b.rpcTo((List<String>) l);
        Object calledBy = m.get("called_by");
        if (calledBy instanceof List<?> l) b.calledBy((List<String>) l);
        Object packages = m.get("packages");
        if (packages instanceof List<?> l) b.packages((List<String>) l);
        Object features = m.get("features");
        if (features instanceof List<?> l) {
            List<FeatureInfo> fl = new ArrayList<>(l.size());
            for (Object f : l) {
                if (f instanceof Map<?, ?> fm) fl.add(parseFeature(fm));
            }
            b.features(fl);
        }
        return b.build();
    }

    @SuppressWarnings("unchecked")
    private FeatureInfo parseFeature(Map<?, ?> m) {
        FeatureInfo.FeatureInfoBuilder b = FeatureInfo.builder();
        b.name((String) m.get("name"));
        b.priority((String) m.get("priority"));
        Object keywords = m.get("keywords");
        if (keywords instanceof List<?> l) b.keywords((List<String>) l);
        Object rpcTo = m.get("rpc_to");
        if (rpcTo instanceof String s) {
            b.rpcTo(List.of(s));
        } else if (rpcTo instanceof List<?> l) {
            b.rpcTo((List<String>) l);
        }
        return b.build();
    }

    public List<ProjectInfo> getProjects() {
        return projects;
    }

    public ProjectInfo findByName(String name) {
        if (name == null) return null;
        return projects.stream()
                .filter(p -> name.equals(p.getName()))
                .findFirst()
                .orElse(null);
    }
}
