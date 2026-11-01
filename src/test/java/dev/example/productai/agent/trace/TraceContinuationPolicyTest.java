package dev.example.productai.agent.trace;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TraceContinuationPolicyTest {

    private final TraceContinuationPolicy policy = new TraceContinuationPolicy();

    @Test
    void rejectsContinuationWordingWhenUserExplicitlySwitchesTopic() {
        TraceState previous = TraceState.start("s", "团队 token 失效问题为什么发生", "user", null);

        assertThat(policy.shouldResume("继续聊别的，帮我查订单接口", previous)).isFalse();
    }

    @Test
    void acceptsStandaloneContinuationCommand() {
        TraceState previous = TraceState.start("s", "查询商品保存调用链", "user", null);

        assertThat(policy.shouldResume("继续查到底", previous)).isTrue();
    }

    @Test
    void acceptsContinuationWithSharedTechnicalAnchor() {
        TraceState previous = TraceState.start("s", "BatchItemService 调用链没有闭环", "dev", null);

        assertThat(policy.shouldResume("继续查 BatchItemService 的实现", previous)).isTrue();
    }

    @Test
    void rejectsUnrelatedSubjectEvenWhenItStartsWithContinue() {
        TraceState previous = TraceState.start("s", "团队 token 失效问题为什么发生", "user", null);

        assertThat(policy.shouldResume("继续查订单库存扣减", previous)).isFalse();
        assertThat(policy.shouldResume("继续查订单调用链", previous)).isFalse();
    }
}
