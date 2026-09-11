package cn.county.agrimach.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 驾驶员：资质决定可驾驶机具/可执行作业；累计工时用于疲劳度监测 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "driver")
public class Driver {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    private UserAccount user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Cooperative coop;

    @Column(nullable = false, length = 40)
    private String name;

    /** 资质：可操作的农机类型，逗号分隔 MachineType 名称 */
    @Column(nullable = false, length = 200)
    private String qualifications;

    /** 当日累计工作分钟（演示按自然日，合作社视图按此判断疲劳） */
    @Column(nullable = false)
    private Integer todayWorkMinutes = 0;

    /** 连续作业疲劳阈值（分钟） */
    @Column(nullable = false)
    private Integer fatigueLimitMinutes = 480;

    @Column(length = 30)
    private String licenseNo;

    public boolean qualifiedFor(String machineTypeName) {
        if (qualifications == null) return false;
        for (String q : qualifications.split(",")) {
            if (q.trim().equalsIgnoreCase(machineTypeName)) return true;
        }
        return false;
    }
}
