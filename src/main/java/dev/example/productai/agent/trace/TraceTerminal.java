package dev.example.productai.agent.trace;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 已由源码证据确认的数据或系统终点。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TraceTerminal {
    private String symbol;
    private String type;
    private String evidenceFile;
    private Integer evidenceLine;
    private String detail;

    public String identity() {
        return normalize(symbol) + "|" + normalize(type) + "|" + normalize(evidenceFile);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }
}
