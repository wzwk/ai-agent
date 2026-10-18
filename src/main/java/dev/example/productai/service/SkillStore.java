package dev.example.productai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import dev.example.productai.model.SkillItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 技能库存储：JSON 文件持久化（默认 data/skills.json），进程重启不丢。
 * 所有人提交的技能全员共享。
 
 * @author wengzewei*/
@Slf4j
@Component
public class SkillStore {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path file;
    private final List<SkillItem> skills = new ArrayList<>();

    public SkillStore(@Value("${app.skills-file:data/skills.json}") String file) {
        this.file = Paths.get(file);
        load();
    }

    private void load() {
        try {
            if (!Files.exists(file)) {
                log.info("技能库文件不存在，初始化空库: {}", file.toAbsolutePath());
                return;
            }
            CollectionType type = mapper.getTypeFactory()
                    .constructCollectionType(List.class, SkillItem.class);
            List<SkillItem> list = mapper.readValue(file.toFile(), type);
            synchronized (skills) {
                skills.clear();
                if (list != null) skills.addAll(list);
            }
            log.info("技能库加载完成: {} 条", skills.size());
        } catch (Exception e) {
            log.warn("技能库加载失败（忽略并使用空库）: {}", file, e);
        }
    }

    public synchronized List<SkillItem> list() {
        synchronized (skills) {
            return new ArrayList<>(skills);
        }
    }

    public synchronized SkillItem add(SkillItem s) {
        s.setId(UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        s.setCreatedAt(System.currentTimeMillis());
        if (s.getUseCount() == null) s.setUseCount(0);
        synchronized (skills) {
            skills.add(0, s); // 新的排前面
        }
        persist();
        log.info("新技能入库: {} ({})", s.getName(), s.getId());
        return s;
    }

    public synchronized boolean delete(String id) {
        boolean removed;
        synchronized (skills) {
            removed = skills.removeIf(x -> x.getId().equals(id));
        }
        if (removed) persist();
        return removed;
    }

    /** 使用次数 +1，返回新的次数；找不到返回 -1 */
    public synchronized int incrUse(String id) {
        synchronized (skills) {
            for (SkillItem s : skills) {
                if (s.getId().equals(id)) {
                    s.setUseCount(s.getUseCount() + 1);
                    persist();
                    return s.getUseCount();
                }
            }
        }
        return -1;
    }

    private void persist() {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            synchronized (skills) {
                mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), skills);
            }
        } catch (Exception e) {
            log.error("技能库写入失败: {}", file, e);
        }
    }
}
