package cn.county.agrimach.domain.entity;

import cn.county.agrimach.domain.enums.E;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 补贴申请明细：每条作业 × 每个分段类型一行，金额可回溯到作业版本与分段 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "subsidy_claim_item", indexes = {
        @Index(name = "idx_item_claim", columnList = "claim_id"),
        @Index(name = "idx_item_order", columnList = "work_order_id")
})
public class SubsidyClaimItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @com.fasterxml.jackson.annotation.JsonBackReference
    private SubsidyClaim claim;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private WorkOrder workOrder;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private E.SegmentType segmentType;

    @Column(nullable = false)
    private Double quantity;

    @Column(length = 10)
    private String basis;

    @Column(nullable = false)
    private Double rate;

    @Column(nullable = false)
    private Double amount;

    /** 取数时的费用版本，与重算记录对应 */
    @Column(nullable = false)
    private Integer calcVersion;
}
