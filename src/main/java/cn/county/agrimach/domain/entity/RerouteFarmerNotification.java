package cn.county.agrimach.domain.entity;

import cn.county.agrimach.domain.enums.E;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 重排农户通知：受阻地块农户收到“转场/等待/延期”通知，
 * 其响应（接受延期/要求换机具/取消作业）回写并计入合作社调度评分。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "reroute_farmer_notification",
        indexes = @Index(name = "idx_rn_plan", columnList = "plan_id"))
public class RerouteFarmerNotification {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @com.fasterxml.jackson.annotation.JsonBackReference
    private ReroutePlan plan;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private WorkOrder workOrder;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private UserAccount farmer;

    @Column(nullable = false, length = 500)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private E.NotificationStatus notifyStatus = E.NotificationStatus.UNSENT;

    private LocalDateTime sentAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private E.FarmerResponse response = E.FarmerResponse.PENDING;
    private LocalDateTime respondedAt;
    @Column(length = 300)
    private String responseNote;
    /** 农户满意度影响（分）：接受延期小幅扣分，换机/取消大幅扣分 */
    @Column(nullable = false)
    private Integer satisfactionImpact = 0;
    /** 换机/取消的处置完成标记（合作社重新派机或订单取消） */
    @Column(nullable = false)
    private boolean handled = false;
}
