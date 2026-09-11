package cn.county.agrimach.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 补贴审核附件：面积复核结论同步到补贴申报单的凭证。
 * 复核证据（预约/轨迹/卫星边界/驾驶员备注/验收照片）随附件进入审核，
 * 并标注补贴差额，提示审核部门驳回后由合作社按新数据重新申报。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "subsidy_attachment", indexes = @Index(name = "idx_att_claim", columnList = "claim_id"))
public class SubsidyAttachment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @com.fasterxml.jackson.annotation.JsonBackReference
    private SubsidyClaim claim;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private WorkOrder workOrder;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private AreaReview areaReview;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private Double oldAreaMu;
    private Double newAreaMu;
    private Double feeDelta;
    private Double subsidyDelta;
    private Integer oldCalcVersion;
    private Integer newCalcVersion;

    /** 复核证据快照（与 AreaReview.evidenceSnapshot 一致） */
    @Column(length = 2000)
    private String evidenceSnapshot;

    /** 审核提示（如：草稿已自动重算 / 已申报请驳回重报 / 已核拨另生成调整单） */
    @Column(length = 500)
    private String handlingNote;
}
