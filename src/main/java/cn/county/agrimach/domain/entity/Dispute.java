package cn.county.agrimach.domain.entity;

import cn.county.agrimach.domain.enums.E;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** 纠纷处理记录：与作业单、异常、签批同档，处理结果可追溯 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "dispute", indexes = @Index(name = "idx_dispute_order", columnList = "work_order_id"))
public class Dispute {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private WorkOrder workOrder;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private UserAccount raisedBy;

    @Column(nullable = false, length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private E.DisputeStatus status = E.DisputeStatus.OPEN;

    @Column(nullable = false)
    private LocalDateTime raisedAt = LocalDateTime.now();

    /** 调解人（村干部/合作社） */
    @ManyToOne(fetch = FetchType.LAZY)
    private UserAccount mediator;

    @Column(length = 500)
    private String resolution;
    private LocalDateTime resolvedAt;
}
