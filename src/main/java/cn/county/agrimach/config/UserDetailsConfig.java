package cn.county.agrimach.config;

import cn.county.agrimach.domain.entity.UserAccount;
import cn.county.agrimach.domain.repo.Repos;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

/** 账号体系：五类角色（合作社/驾驶员/农户/村干部/补贴审核部门）共用一张用户表 */
@Configuration
@RequiredArgsConstructor
public class UserDetailsConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(Repos.UserRepo userRepo) {
        return username -> {
            UserAccount u = userRepo.findByUsername(username)
                    .orElseThrow(() -> new UsernameNotFoundException("账号不存在: " + username));
            return User.withUsername(u.getUsername())
                    .password(u.getPassword())
                    .authorities(List.of(new SimpleGrantedAuthority("ROLE_" + u.getRole().name())))
                    .disabled(!u.isEnabled())
                    .build();
        };
    }
}
