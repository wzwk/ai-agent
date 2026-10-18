package dev.example.productai.llm;

/**
 * 用户主动取消 LLM 生成时抛出；上层按正常业务处理，不应记为系统错误
 *
 * @author wengzewei
 */
public class LlmCancelledException extends RuntimeException {

    public LlmCancelledException() {
        super("LLM 生成已取消");
    }
}
