package cn.county.agrimach.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 重算留痕：每次异常重排都生成一条版本记录，
 * 记录作业时间 / 农户费用 / 油补调整前后值，补贴审核时可逐条解释重排原因。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "recalculation", indexes = @Index(name = "idx_recalc_order", columnList = "work_order_id"))
public class Recalculation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private WorkOrder workOrder;

    @ManyToOne(fetch = FetchType.LAZY)
    private WorkException workException;

    /** 版本号（= 重算后 WorkOrder.calcVersion） */
    @Column(nullable = false)
    private Integer version;

    @Column(nullable = false, length = 300)
    private String reason;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    private String triggerRole;

    // ---------- 调整前后对照 ----------
    private Double oldAreaMu;
    private Double newAreaMu;
    private Integer oldWorkMinutes;
    private Integer newWorkMinutes;
    private Double oldFarmerFee;
    private Double newFarmerFee;
    private Double oldSubsidy;
    private Double newSubsidy;

    /** 本次分段重算明细（分段类型→金额 的可读文本，供审核解释） */
    @Column(length = 1000)
    private String segmentDeltaText;
}
