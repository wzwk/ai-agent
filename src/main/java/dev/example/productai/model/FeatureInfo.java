package dev.example.productai.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.List;

/**
 * 项目下的功能切片（如 sample-gateway 的"动态页查询"）
 * 用于细粒度路由和跨服务依赖追踪
 
 * @author wengzewei*/
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeatureInfo {
    private String name;
    private List<String> keywords;

    /** 该 feature 会 RPC 调用到的目标项目名（单个或多个） */
    private List<String> rpcTo;

    /** 优先级：P0/P1/P2，未填默认 P1 */
    private String priority;
}
