package dev.example.productai.controller;

import dev.example.productai.model.SkillItem;
import dev.example.productai.service.SkillStore;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 技能库接口：收集/共享大家的接口分析姿势
 
 * @author wengzewei*/
@RestController
@RequestMapping("/api/skills")
@RequiredArgsConstructor
public class SkillController {

    private final SkillStore store;

    @GetMapping
    public List<SkillItem> list() {
        return store.list();
    }

    @PostMapping
    public ResponseEntity<?> add(@RequestBody SkillItem skill) {
        if (skill.getName() == null || skill.getName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name 必填"));
        }
        return ResponseEntity.ok(store.add(skill));
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        return Map.of("ok", store.delete(id));
    }

    /** 使用次数 +1（载入技能时调用） */
    @PostMapping("/{id}/use")
    public Map<String, Object> use(@PathVariable String id) {
        return Map.of("count", store.incrUse(id));
    }
}
