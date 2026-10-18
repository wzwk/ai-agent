package dev.example.productai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "projects")
public class ProjectsProperties {
    /** projects.yml 的位置 
 * @author wengzewei*/
    private String configLocation = "classpath:projects.yml";
    /** 本地代码根目录 */
    private String codeBase;
}
