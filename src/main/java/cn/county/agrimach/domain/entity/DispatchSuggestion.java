package cn.county.agrimach.domain.entity;

import cn.county.agrimach.domain.enums.E;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 派机建议：每次调度可为每个合作社生成候选，记录评分原因便于解释 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "dispatch_suggestion", indexes = @Index(name = "idx_sugg_order", columnList = "work_order_id"))
public class DispatchSuggestion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private WorkOrder workOrder;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Cooperative coop;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Machine machine;

    @ManyToOne(fetch = FetchType.LAZY)
    private Driver driver;

    @Column(nullable = false)
    private Double roadDistanceKm;

    /** 排班上的最早可出发时间 */
    @Column(nullable = false)
    private java.time.LocalDateTime earliestDeparture;

    /** 预计到地时间 */
    @Column(nullable = false)
    private java.time.LocalDateTime estimatedArrival;

    @Column(nullable = false)
    private Integer estimatedWorkMinutes;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private E.WeatherType weather;

    /** 综合评分（越低越优） */
    @Column(nullable = false)
    private Double score;

    /** 预计总费用 / 预计油补 */
    @Column(nullable = false)
    private Double estimatedFee;
    @Column(nullable = false)
    private Double estimatedSubsidy;

    /** 评分明细解释，如 “距离近+30; 资质匹配+0; 疲劳风险+15” */
    @Column(length = 500)
    private String reasonText;

    /** 是否最终采纳派机 */
    @Column(nullable = false)
    private boolean selected = false;

    public DispatchSuggestion(WorkOrder workOrder, Cooperative coop, Machine machine, Driver driver) {
        this.workOrder = workOrder;
        this.coop = coop;
        this.machine = machine;
        this.driver = driver;
    }
}
