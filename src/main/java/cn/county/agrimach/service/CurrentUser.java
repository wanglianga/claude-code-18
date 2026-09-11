package cn.county.agrimach.service;

import cn.county.agrimach.domain.entity.UserAccount;
import cn.county.agrimach.domain.repo.Repos;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CurrentUser {

    private final Repos.UserRepo userRepo;

    public UserAccount get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new IllegalStateException("未登录");
        }
        return userRepo.findByUsername(auth.getName())
                .orElseThrow(() -> new IllegalStateException("账号不存在: " + auth.getName()));
    }

    public String username() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
