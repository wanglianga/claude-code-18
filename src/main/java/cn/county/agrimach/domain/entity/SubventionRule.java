package cn.county.agrimach.domain.entity;

import cn.county.agrimach.domain.enums.E;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 油料补贴规则（按作业类型 + 分段类型配置）：
 * 空驶按 元/km 补、等待天气按 元/小时 补、有效作业按 元/亩 补、
 * 返工/机具故障按 元/小时 补（且故障段补贴通常下浮，避免补贴与实际服务脱节）。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "subvention_rule",
        uniqueConstraints = @UniqueConstraint(columnNames = {"operation_type", "segment_type"}))
public class SubventionRule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false, length = 30)
    private E.OperationType operationType;

    @Enumerated(EnumType.STRING)
    @Column(name = "segment_type", nullable = false, length = 20)
    private E.SegmentType segmentType;

    /** 计量口径：MU（亩）/ KM（公里）/ HOUR（小时） */
    @Column(nullable = false, length = 10)
    private String basis;

    /** 补贴标准（元 / 计量单位） */
    @Column(nullable = false)
    private Double rate;

    /** 是否纳入补贴（机具故障段可设为 false 或下浮） */
    @Column(nullable = false)
    private boolean payable = true;

    /** 补贴系数（如泥泞、故障下浮），默认 1.0 */
    @Column(nullable = false)
    private Double factor = 1.0;

    @Column(length = 200)
    private String note;
}
