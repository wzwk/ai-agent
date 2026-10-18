package dev.example.productai.controller;

import dev.example.productai.config.ProjectsConfigLoader;
import dev.example.productai.model.ProjectInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 查看已加载的项目元数据
 
 * @author wengzewei*/
@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectsController {

    private final ProjectsConfigLoader loader;

    @GetMapping
    public List<ProjectInfo> all() {
        return loader.getProjects();
    }
}
