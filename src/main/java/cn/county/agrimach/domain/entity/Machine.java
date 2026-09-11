package cn.county.agrimach.domain.entity;

import cn.county.agrimach.domain.enums.E;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 农机具：类型决定可接作业类型；小时台账支撑维修窗口与利用率 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "machine")
public class Machine {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Cooperative coop;

    @Column(nullable = false, unique = true, length = 30)
    private String code;

    @Column(nullable = false, length = 40)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private E.MachineType type;

    /** 可承担作业类型，逗号分隔的 OperationType 名称 */
    @Column(nullable = false, length = 200)
    private String supportedOps;

    @Column(nullable = false)
    private boolean enabled = true;

    /** 当前累计工作小时 */
    @Column(nullable = false)
    private Double hourMeter = 0.0;

    /** 上次保养小时 / 保养间隔（小时） */
    @Column(nullable = false)
    private Double lastMaintenanceHour = 0.0;

    @Column(nullable = false)
    private Double maintenanceIntervalHours = 200.0;

    /** 单日作业时长上限（疲劳与排班约束） */
    @Column(nullable = false)
    private Integer dailyCapMinutes = 540;

    /** 典型小时油耗（L/h），用于出发油量与油耗核算 */
    @Column(nullable = false)
    private Double hourlyFuelL = 18.0;

    /** 平均道路行驶速度 km/h（空驶耗时估算） */
    @Column(nullable = false)
    private Double roadSpeedKmh = 25.0;

    /** 可进地的最高土壤相对湿度（%）：履带式高、轮式低，雨后窗口重排依据 */
    @Column(nullable = false)
    private Double maxSoilMoisturePct = 82.0;

    /** 平均纯作业效率 亩/小时 */
    @Column(nullable = false)
    private Double workRateMuPerHour = 8.0;

    public boolean supports(E.OperationType op) {
        if (supportedOps == null) return false;
        for (String s : supportedOps.split(",")) {
            if (s.trim().equalsIgnoreCase(op.name())) return true;
        }
        return false;
    }
}
