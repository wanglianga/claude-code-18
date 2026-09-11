package cn.county.agrimach.domain.entity;

import cn.county.agrimach.domain.enums.E;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 现场异常工单（五类）：
 * 地块边界不清 / 实际面积大于预约 / 雨后无法进地 / 机具故障 / 农户临时增加作业。
 * 每一次异常都触发一次重排重算，并把现场证据带入档案。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "work_exception", indexes = @Index(name = "idx_exc_order", columnList = "work_order_id"))
public class WorkException {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private WorkOrder workOrder;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private E.ExceptionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private E.ExceptionState state = E.ExceptionState.OPEN;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private UserAccount reportedBy;

    @Column(nullable = false)
    private LocalDateTime reportedAt = LocalDateTime.now();

    @Column(nullable = false, length = 500)
    private String description;

    /** 现场证据描述（照片编号/定位/见证人等，演示用文本） */
    @Column(length = 500)
    private String evidence;

    // ---------- 异常量化输入（决定重算幅度） ----------
    /** 实测面积（面积超标/边界不清时） */
    private Double measuredAreaMu;
    /** 雨后/故障导致的等待停机分钟 */
    private Integer downtimeMinutes = 0;
    /** 返工面积（亩） */
    private Double reworkAreaMu = 0.0;
    /** 农户临时增加的作业类型 */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private E.OperationType addedOperation;
    /** 临时追加作业的面积（亩） */
    private Double addedAreaMu = 0.0;
    /** 雨后折返/调换新机产生的额外空驶里程 km（给补贴、不重复向农户收费） */
    private Double extraEmptyHaulKm = 0.0;

    /** 处置方案（调度意见） */
    @Column(length = 500)
    private String resolution;
    private LocalDateTime resolvedAt;

    /** 报告时的费用版本 */
    @Column(nullable = false)
    private Integer calcVersionAtReport;
}
