package dev.example.productai.config;

import org.apache.coyote.http11.Http11Nio2Protocol;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Windows 本地演示环境使用 NIO2，避免部分 JDK 21 环境创建 WEPoll selector 时
 * 出现 Unable to establish loopback connection。HTTP 行为和接口保持不变。
 */
@Configuration
public class TomcatConfig {

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> tomcatNio2Customizer() {
        return factory -> factory.setProtocol(Http11Nio2Protocol.class.getName());
    }
}
