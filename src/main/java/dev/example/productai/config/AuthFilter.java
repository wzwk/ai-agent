package dev.example.productai.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 简单访问令牌鉴权：
 * - application.yml 里 app.auth.token 为空 => 鉴权关闭（本地使用零影响）
 * - 配置了 token 后，除健康检查外的 /api/** 都必须带 X-Auth-Token 头（或 ?token= 参数）
 * 用于把服务暴露给他人体验时防止被白嫖 LLM 额度 / 泄露源码。
 
 * @author wengzewei*/
@Component
@Order(1)
public class AuthFilter implements Filter {

    @Value("${app.auth.token:}")
    private String token;

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        if (token == null || token.isBlank()) {
            chain.doFilter(req, res);
            return;
        }

        String path = request.getRequestURI();
        boolean protectedApi = path.startsWith("/api/") && !path.equals("/api/chat/health");
        if (protectedApi) {
            String given = request.getHeader("X-Auth-Token");
            if (given == null || given.isBlank()) {
                given = request.getParameter("token");
            }
            if (!token.equals(given)) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"unauthorized\",\"msg\":\"缺少或错误的访问令牌\"}");
                return;
            }
        }
        chain.doFilter(req, res);
    }
}
