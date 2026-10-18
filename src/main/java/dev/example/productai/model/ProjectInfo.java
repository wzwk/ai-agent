package dev.example.productai.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.List;

/**
 * 项目元数据，对应 projects.yml 里的一条
 
 * @author wengzewei*/
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectInfo {
    private String name;
    /** 相对路径（相对于 code-base） */
    private String path;
    private String description;
    private List<String> tags;
    private List<FeatureInfo> features;

    /** 该项目会 RPC 调用的目标项目列表 */
    private List<String> rpcTo;

    /** 调用过该项目的上游项目列表 */
    private List<String> calledBy;

    /** 该项目的 Java 包根列表，用于从代码 import 里识别跨项目引用（引用发现） */
    private List<String> packages;

    /** 优先级：P0/P1/P2 */
    private String priority;

    /**
     * 获取绝对路径
     */
    public String resolveAbsolutePath(String codeBase) {
        if (path == null || path.isBlank()) {
            return codeBase;
        }
        String base = codeBase.endsWith("/") || codeBase.endsWith("\\") ?
                codeBase.substring(0, codeBase.length() - 1) : codeBase;
        return base + "/" + path;
    }
}
