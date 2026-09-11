package cn.county.agrimach.domain.entity;

import cn.county.agrimach.domain.enums.E;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 作业分段（油补核算的最小证据单元）：
 * 一条作业由若干分段组成 —— 空驶 / 有效作业 / 等待天气 / 返工 / 机具故障。
 * 每段挂到某次重算版本（calcVersion）上，费用调整可逐段追溯。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "work_segment", indexes = {
        @Index(name = "idx_seg_order", columnList = "work_order_id"),
        @Index(name = "idx_seg_type", columnList = "segment_type")
})
public class WorkSegment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private WorkOrder workOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "segment_type", nullable = false, length = 20)
    private E.SegmentType segmentType;

    /** 本段对应的作业类型（追加作业可能与主作业不同） */
    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", length = 30)
    private E.OperationType operationType;

    @Column(nullable = false)
    private LocalDateTime startTime;

    @Column(nullable = false)
    private LocalDateTime endTime;

    /** 持续分钟 */
    @Column(nullable = false)
    private Integer durationMinutes;

    /** 本段作业面积（亩）；空驶/等待/故障段为 0 */
    @Column(nullable = false)
    private Double areaMu = 0.0;

    /** 本段行驶里程（km）；空驶段使用 */
    @Column(nullable = false)
    private Double distanceKm = 0.0;

    /** 本段油耗 L */
    @Column(nullable = false)
    private Double fuelL = 0.0;

    /** 依据的补贴标准（元/计量单位） */
    private Double ruleRate;

    @Column(nullable = false)
    private Double subsidyAmount = 0.0;

    /** 所属费用版本（对应 WorkOrder.calcVersion） */
    @Column(nullable = false)
    private Integer calcVersion = 1;

    /** 关联的异常/重算记录 id（可为空） */
    private Long exceptionId;

    @Column(length = 200)
    private String note;
}
