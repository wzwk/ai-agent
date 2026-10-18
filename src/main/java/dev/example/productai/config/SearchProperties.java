package dev.example.productai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@Data
@ConfigurationProperties(prefix = "search")
public class SearchProperties {
    /** 每个项目检索后保留的 top N 文件 
 * @author wengzewei*/
    private int topNPerProject = 5;
    /** LLM 最终精读的文件数上限 */
    private int maxFilesToLlm = 6;
    /** 单个文件读取的最大行数 */
    private int maxLinesPerFile = 300;
    /** 忽略的目录 */
    private List<String> excludeDirs = List.of("target", ".git", ".idea", "node_modules", "build", "dist");
    /** 忽略的文件扩展名 */
    private List<String> excludeExts = List.of(".class", ".jar", ".war", ".log", ".bin");

    /** 同一次搜索并行扫描的项目数；磁盘型任务不宜无限放大 */
    private int parallelism = 4;

    /** 项目文件清单缓存时间；文件内容本身仍按 size + mtime 校验 */
    private long fileListCacheTtlMs = 10_000;

    /** 文件内容缓存最大条目数，使用 LRU 淘汰 */
    private int contentCacheMaxEntries = 50_000;

    /** 文件内容缓存最大估算内存（MB），与条目数任一超限都会淘汰 */
    private int contentCacheMaxMb = 640;

    /** Java 文件名符号索引刷新周期；用于类/接口精确定位，过期后重新扫描目录。 */
    private long symbolIndexTtlMs = 60_000;
}
