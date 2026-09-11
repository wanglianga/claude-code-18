package cn.county.agrimach.domain.entity;

import cn.county.agrimach.domain.enums.E;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** 统一登录账号：合作社 / 驾驶员 / 农户 / 村干部 / 补贴审核部门 都挂在同一条用户体系上 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "sys_user")
public class UserAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String username;

    @Column(nullable = false, length = 100)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String password;

    @Column(nullable = false, length = 40)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private E.Role role;

    @ManyToOne(fetch = FetchType.LAZY)
    private Cooperative coop;

    /** 所属村（农户 / 村干部使用） */
    @Column(length = 60)
    private String village;

    @Column(length = 20)
    private String phone;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public UserAccount(String username, String password, String displayName, E.Role role) {
        this.username = username;
        this.password = password;
        this.displayName = displayName;
        this.role = role;
    }
}
