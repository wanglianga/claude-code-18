package cn.county.agrimach.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** 作业发票：作业费、空驶费、等待费分项列示，进入统一作业档案 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "invoice")
public class Invoice {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    private WorkOrder workOrder;

    @Column(nullable = false, unique = true, length = 40)
    private String invoiceNo;

    @Column(nullable = false)
    private LocalDateTime issuedAt = LocalDateTime.now();

    @Column(nullable = false)
    private Double workFee;
    @Column(nullable = false)
    private Double emptyHaulFee;
    @Column(nullable = false)
    private Double waitingFee;
    @Column(nullable = false)
    private Double totalAmount;

    /** 税率与税额（演示简化） */
    @Column(nullable = false)
    private Double taxRate = 0.03;
    @Column(nullable = false)
    private Double taxAmount;

    @Column(length = 200)
    private String title;
}
