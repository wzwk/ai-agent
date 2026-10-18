package dev.example.productai.agent.trace;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 一条需要验证的代码调用边。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TraceEdge {
    private String fromSymbol;
    private String toSymbol;
    private String kind;
    private String status;
    private String evidenceFile;
    private Integer evidenceLine;
    private String reason;

    public String identity() {
        return normalize(fromSymbol) + "->" + normalize(toSymbol) + "|" + normalize(kind);
    }

    public boolean isPending() {
        return "pending".equalsIgnoreCase(status);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }
}
