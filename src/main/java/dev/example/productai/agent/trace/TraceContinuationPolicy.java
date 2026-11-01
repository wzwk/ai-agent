package dev.example.productai.agent.trace;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 判断本轮输入是否应该恢复最近一次未完成调用链。
 *
 * <p>恢复属于有状态路由，宁可把含糊输入当作新任务，也不能把无关问题挂到旧状态图上。</p>
 */
@Component
public class TraceContinuationPolicy {

    private static final Pattern CONTINUATION_CUE = Pattern.compile(
            "继续深挖|继续查|继续|接着查|接着|往下查|没闭环|查到底");
    private static final Pattern NEW_TOPIC_CUE = Pattern.compile(
            "聊别的|查别的|问别的|另一个|另外一个|其他问题|换个|换一个|新问题|重新问|顺便问");
    private static final Pattern PRIOR_REFERENCE = Pattern.compile(
            "上面|刚才|之前|前面|这个问题|该问题|原问题|原来的|剩下|未完成|同一条|这条链路");
    private static final Pattern CONTINUATION_ONLY = Pattern.compile(
            "^(?:请)?(?:继续深挖|继续查|继续|接着查|接着|往下查|没闭环|查到底)"
                    + "(?:一下|下去|看看|吧|呀|哈|这个问题|该问题|上面|刚才|原问题|剩下的|未完成的部分|查到底)*[，,。.!！?？\\s]*$");
    private static final Pattern TECHNICAL_TOKEN = Pattern.compile(
            "(?:/[A-Za-z0-9_./{}:-]{3,})|(?:[A-Za-z_$][A-Za-z0-9_$./:#-]{2,})");
    private static final Pattern HAN_SEQUENCE = Pattern.compile("[\\p{IsHan}]{2,}");
    private static final Set<String> GENERIC_BIGRAMS = Set.of(
            "继续", "问题", "这个", "那个", "帮我", "一下", "看看", "查询", "查下",
            "接口", "代码", "调用", "逻辑", "分析", "服务", "项目", "链路", "为什么", "怎么");

    public boolean hasContinuationIntent(String question) {
        return question != null && CONTINUATION_CUE.matcher(normalize(question)).find();
    }

    public boolean shouldResume(String question, TraceState candidate) {
        if (candidate == null || question == null) return false;
        String normalized = normalize(question);
        if (!CONTINUATION_CUE.matcher(normalized).find()) return false;
        if (NEW_TOPIC_CUE.matcher(normalized).find()) return false;
        if (CONTINUATION_ONLY.matcher(normalized).matches()) return true;
        if (PRIOR_REFERENCE.matcher(normalized).find()) return true;

        String previous = String.join(" ", nullToEmpty(candidate.getOriginalQuestion()),
                nullToEmpty(candidate.getLatestQuestion()));
        return sharesTechnicalToken(normalized, normalize(previous))
                || sharesMeaningfulChineseBigram(normalized, normalize(previous));
    }

    private boolean sharesTechnicalToken(String current, String previous) {
        Set<String> oldTokens = matches(TECHNICAL_TOKEN, previous);
        for (String token : matches(TECHNICAL_TOKEN, current)) {
            if (oldTokens.contains(token)) return true;
        }
        return false;
    }

    private boolean sharesMeaningfulChineseBigram(String current, String previous) {
        Set<String> oldBigrams = chineseBigrams(previous);
        for (String bigram : chineseBigrams(current)) {
            if (oldBigrams.contains(bigram)) return true;
        }
        return false;
    }

    private Set<String> matches(Pattern pattern, String text) {
        Set<String> result = new HashSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) result.add(matcher.group().toLowerCase(Locale.ROOT));
        return result;
    }

    private Set<String> chineseBigrams(String text) {
        Set<String> result = new HashSet<>();
        Matcher matcher = HAN_SEQUENCE.matcher(text);
        while (matcher.find()) {
            String sequence = matcher.group();
            for (int i = 0; i + 2 <= sequence.length(); i++) {
                String bigram = sequence.substring(i, i + 2);
                if (!GENERIC_BIGRAMS.contains(bigram)) result.add(bigram);
            }
        }
        return result;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
