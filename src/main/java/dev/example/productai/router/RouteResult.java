package dev.example.productai.router;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 路由结果：包含命中的项目、feature、扩展（RPC 依赖）、检索关键词
 
 * @author wengzewei*/
@Data
@Builder
public class RouteResult {
    /** 用户原始问题 */
    private String question;
    /** 命中的项目名（已经展开 RPC 依赖） */
    private List<String> projectNames;
    /** 命中的 feature 名（来自 sample-album-bff 等） */
    private List<String> featureNames;
    /** 用于代码检索的关键词（合并 tags + feature keywords + 提取词） */
    private List<String> searchKeywords;
    /** 是否非商品问题（true 则不进入主流程） */
    private boolean nonProductRelated;
    /** 路由原因（debug 用） */
    private String reason;
}
