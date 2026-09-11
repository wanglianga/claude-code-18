package cn.county.agrimach.domain.entity;

import cn.county.agrimach.domain.enums.E;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 地块面积争议复核：
 * 农户认为收费面积过高时发起；复核自动归集五类证据——
 * 预约面积、驾驶轨迹核算面积、卫星地块边界、驾驶员备注、农户验收照片。
 * 裁决后：超算→费用/油补同步退减并调发票；少报→记农户诚信风险，后续预约须先确认地块边界。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "area_review", indexes = @Index(name = "idx_review_order", columnList = "work_order_id"))
public class AreaReview {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private WorkOrder workOrder;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private UserAccount raisedBy;

    @Column(nullable = false)
    private LocalDateTime raisedAt = LocalDateTime.now();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private E.AreaReviewStatus status = E.AreaReviewStatus.OPEN;

    /** 农户主张的应收面积（认为收费面积过高） */
    @Column(nullable = false)
    private Double farmerClaimAreaMu;

    @Column(nullable = false, length = 500)
    private String reason;

    /** 农户验收照片编号/引用，逗号分隔 */
    @Column(length = 500)
    private String farmerAcceptancePhotoRefs;

    // ---------- 驾驶员备注 ----------
    @Column(length = 500)
    private String driverNote;
    @ManyToOne(fetch = FetchType.LAZY)
    private UserAccount driverNotedBy;
    private LocalDateTime driverNotedAt;

    // ---------- 卫星地块边界证据 ----------
    @Column(length = 300)
    private String satelliteBoundaryRef;
    private Double satelliteBoundaryAreaMu;

    // ---------- 复核裁决（村干部） ----------
    private Double reviewedAreaMu;
    @ManyToOne(fetch = FetchType.LAZY)
    private UserAccount reviewer;
    private LocalDateTime reviewedAt;
    @Column(length = 500)
    private String reviewOpinion;

    /** 复核发起/裁决时的费用版本与证据快照（JSON 文本，进入补贴审核附件） */
    @Column(nullable = false)
    private Integer calcVersionAtRaise;
    private Integer calcVersionAtReview;

    /** 裁决前后对照（同步结算/补贴用） */
    private Double areaBeforeMu;
    private Double feeBefore;
    private Double subsidyBefore;
    private Double feeAfter;
    private Double subsidyAfter;

    /** 五类证据归集快照 */
    @Column(length = 2000)
    private String evidenceSnapshot;
}
