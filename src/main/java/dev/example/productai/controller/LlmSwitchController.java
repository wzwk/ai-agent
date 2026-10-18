package dev.example.productai.controller;

import dev.example.productai.config.LlmProperties;
import dev.example.productai.config.ProjectsConfigLoader;
import dev.example.productai.model.ProjectInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 调试 / 运维接口
 
 * @author wengzewei*/
@RestController
@RequestMapping("/api/debug")
@RequiredArgsConstructor
public class LlmSwitchController {

    private final LlmProperties llmProperties;

    @GetMapping("/providers")
    public Map<String, Object> providers() {
        return Map.of(
                "active", llmProperties.getActive(),
                "available", llmProperties.getProviders() == null ? Map.of() : llmProperties.getProviders().keySet()
        );
    }

    /** 运行时切换 LLM provider（重启后失效，永久切换请改 application.yml） */
    @PostMapping("/switch-provider")
    public Map<String, Object> switchProvider(@RequestParam String name) {
        if (llmProperties.getProviders() == null || !llmProperties.getProviders().containsKey(name)) {
            return Map.of("ok", false, "msg", "未知 provider: " + name);
        }
        llmProperties.setActive(name);
        return Map.of("ok", true, "active", name);
    }
}
