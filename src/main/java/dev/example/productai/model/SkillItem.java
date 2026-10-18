package dev.example.productai.model;

import lombok.Builder;
import lombok.Data;

/**
 * 一条"技能"：别人沉淀下来的可复用提问/分析姿势。全员共享。
 * type=prompt  → 自由提问模板（promptText + 建议 mode），适用于任何聊天场景
 * type=analyze → 接口分析配置（projects/apiPath/intent/flowchart），载入到接口分析表单
 
 * @author wengzewei*/
@Data
@Builder
public class SkillItem {
    /** 短 ID（8 位随机串） */
    private String id;

    /** 技能类型：analyze / prompt */
    private String type;

    /** 技能名，如"保存接口全链路排查"、"报错排查万能模板" */
    private String name;
    /** 说明：什么时候用它、注意什么 */
    private String desc;

    /** ===== type=prompt ===== */
    /** 模板正文，可含占位说明文字；载入时填入输入框 */
    private String promptText;
    /** 建议回答模式：user / dev */
    private String mode;

    /** ===== type=analyze ===== */
    /** 指定项目列表 */
    private java.util.List<String> projects;
    /** 接口路径 */
    private String apiPath;
    /** 分析目标文本 */
    private String intent;
    /** 是否生成流程图 */
    private Boolean flowchart;

    /** 提交人昵称（选填，前端自行填写） */
    private String author;
    /** 创建时间戳 */
    private Long createdAt;
    /** 使用次数 */
    private Integer useCount;
}
