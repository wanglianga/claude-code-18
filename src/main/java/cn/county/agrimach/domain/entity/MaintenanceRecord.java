package cn.county.agrimach.domain.entity;

import cn.county.agrimach.domain.enums.E;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 维修记录（进入作业档案 + 合作社维修窗口）：
 * 机具故障异常可直接转维修；合作社视图据此安排维修窗口。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "maintenance_record", indexes = @Index(name = "idx_maint_machine", columnList = "machine_id"))
public class MaintenanceRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Machine machine;

    @ManyToOne(fetch = FetchType.LAZY)
    private WorkOrder workOrder;

    @Column(nullable = false, length = 300)
    private String description;

    /** 计划维修窗口开始/结束 */
    private LocalDateTime windowStart;
    private LocalDateTime windowEnd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private E.RepairStatus status = E.RepairStatus.PLANNED;

    @Column(nullable = false)
    private Double cost = 0.0;

    /** 维修时机具小时表读数 */
    private Double hourMeterAtRepair;

    @Column(length = 300)
    private String repairNote;
}
