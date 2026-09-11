package cn.county.agrimach.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 角色边界（业务归属在 Service 层二次校验）：
 * 农户 /api/farmer/**，驾驶员 /api/driver/**，合作社 /api/coop/**，
 * 村干部 /api/village/**，补贴审核部门 /api/auditor/**。
 * 共享的作业单、档案、纠纷接口要求登录，具体可操作范围按本人/本社校验。
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(reg -> reg
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/", "/api/meta/**").permitAll()
                        .requestMatchers("/api/farmer/**").hasRole("FARMER")
                        .requestMatchers("/api/driver/**").hasRole("DRIVER")
                        .requestMatchers("/api/coop/**").hasRole("COOP")
                        .requestMatchers("/api/village/**").hasRole("VILLAGE")
                        .requestMatchers("/api/auditor/**").hasRole("AUDITOR")
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults());
        return http.build();
    }
}
