package dev.example.productai.search;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 检索到的代码片段
 
 * @author wengzewei*/
@Data
@Builder
public class SearchResult {
    /** 文件绝对路径 */
    private String filePath;
    /** 所属项目名 */
    private String projectName;
    /** 命中的关键词 */
    private String matchedKeyword;
    /** 命中行数（用于排序） */
    private int hitCount;
    /** 关键词命中所在行号（1-based，升序，最多记录 MAX_HIT_LINES 条） */
    private List<Integer> hitLines;
    /** 文件总行数 */
    private int totalLines;
    /** 是否精确命中完整 URL 路径关键词（强信号，排序最优先） */
    private boolean pathHit;

    /** 是否命中 URL 路径的片段变体（类级+方法级注解分开声明时完整路径在源码中不存在） */
    private boolean partialPathHit;

    /** 路径匹配具体度得分：完整路径100 / 末两段80 / 方法名60 / 类前缀20；越高越优先 */
    private int pathScore;
    /** 路由注解出现次数（@RequestMapping / @GetMapping 等），用于识别 Controller */
    private int routeAnnotationCount;
}
