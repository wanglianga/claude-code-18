package cn.county.agrimach.domain.entity;

import cn.county.agrimach.domain.enums.E;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 补贴申请（合作社→补贴审核部门）：
 * 汇总跨村多单的分段补贴（空驶/有效作业/等待天气/返工/机具故障），
 * 审核部门可逐条下钻到作业档案（地块、轨迹、分段、现场验收证据）。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "subsidy_claim", indexes = @Index(name = "idx_claim_coop", columnList = "coop_id"))
public class SubsidyClaim {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String claimNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Cooperative coop;

    @Column(nullable = false, length = 20)
    private String period;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private E.SubsidyStatus status = E.SubsidyStatus.DRAFT;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime submittedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    private UserAccount reviewedBy;
    private LocalDateTime reviewedAt;
    @Column(length = 500)
    private String reviewComment;

    // ---------- 分项汇总 ----------
    @Column(nullable = false) private Double emptyHaulSubsidy = 0.0;
    @Column(nullable = false) private Double productiveSubsidy = 0.0;
    @Column(nullable = false) private Double weatherWaitSubsidy = 0.0;
    @Column(nullable = false) private Double reworkSubsidy = 0.0;
    @Column(nullable = false) private Double faultSubsidy = 0.0;
    @Column(nullable = false) private Double totalSubsidy = 0.0;
    @Column(nullable = false) private Integer orderCount = 0;
    /** 有效作业比例（%）= 有效作业时间 /（作业+空驶+等待天气+返工+故障），雨后重排影响该比例 */
    @Column(nullable = false) private Double productiveRatioPct = 0.0;

    @OneToMany(mappedBy = "claim", cascade = CascadeType.ALL, orphanRemoval = true)
    @com.fasterxml.jackson.annotation.JsonManagedReference
    private List<SubsidyClaimItem> items = new ArrayList<>();

    /** 面积复核同步附件（复核证据进入补贴审核） */
    @OneToMany(mappedBy = "claim", cascade = CascadeType.ALL, orphanRemoval = true)
    @com.fasterxml.jackson.annotation.JsonManagedReference
    private List<SubsidyAttachment> attachments = new ArrayList<>();

    /** 存在已裁决复核但本单尚未按新面积重报（审核部门应驳回重报） */
    @Column(nullable = false)
    private boolean needsAdjustment = false;
    @Column(length = 500)
    private String adjustmentNote;
}
