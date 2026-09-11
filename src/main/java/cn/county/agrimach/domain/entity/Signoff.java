package cn.county.agrimach.domain.entity;

import cn.county.agrimach.domain.enums.E;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 三方签批（同一条作业档案内）：
 * 村干部确认、农户签字、合作社调度意见 —— 补贴审核与纠纷处理时可调阅。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "signoff", indexes = @Index(name = "idx_signoff_order", columnList = "work_order_id"))
public class Signoff {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private WorkOrder workOrder;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private E.SignoffType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private E.SignoffStatus status = E.SignoffStatus.PENDING;

    @ManyToOne(fetch = FetchType.LAZY)
    private UserAccount signer;

    @Column(length = 300)
    private String opinion;

    private LocalDateTime signedAt;

    public Signoff(WorkOrder workOrder, E.SignoffType type) {
        this.workOrder = workOrder;
        this.type = type;
    }
}
