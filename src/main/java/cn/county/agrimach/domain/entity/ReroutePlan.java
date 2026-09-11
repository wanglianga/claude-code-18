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
 * 雨后作业窗口重排计划：
 * 某村因降水/土壤湿度无法进机时，系统按土壤湿度、作物成熟紧迫度、
 * 农机当前位置、其他村可作业预约生成重排建议；合作社决策先转场或原地等待。
 * 决策执行后：受影响农户收到通知，其接受延期/要求换机/取消均回写本计划，
 * 并联动空驶油耗、农户满意度与补贴有效作业比例。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "reroute_plan", indexes = {
        @Index(name = "idx_rr_blocked", columnList = "blocked_order_id"),
        @Index(name = "idx_rr_coop", columnList = "coop_id")
})
public class ReroutePlan {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 受阻作业单（无法进机村的地块） */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private WorkOrder blockedOrder;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Cooperative coop;

    /** 当时拟动用的机具（农机位置=合作社驻地或上一作业点） */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Machine machine;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private E.ReroutePlanStatus status = E.ReroutePlanStatus.PROPOSED;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /** 受阻村土壤湿度与降水（决策证据） */
    @Column(nullable = false)
    private Double blockedSoilMoisturePct;
    @Column(nullable = false)
    private Double blockedRainfallMm;

    /** 可进地的最早日期（yyyy-MM-dd，按天气/土壤预报推算） */
    @Column(length = 10)
    private String nextAccessibleDate;
    /** 原地等待晾墒的预计分钟 */
    @Column(nullable = false)
    private Integer proposedWaitMinutes;
    /** 等待期间农户满意度影响（分，负值） */
    @Column(nullable = false)
    private Integer waitSatisfactionImpact;

    /** 转场首选作业单 */
    @ManyToOne(fetch = FetchType.LAZY)
    private WorkOrder bestAlternativeOrder;
    /** 转场增加空驶里程 km（候选点→新地块，替代原地等待） */
    @Column(nullable = false)
    private Double divertExtraKm;
    /** 转场增加空驶分钟 */
    @Column(nullable = false)
    private Integer divertExtraMinutes;
    /** 转场增加空驶油耗 L（计入空驶段油耗与补贴） */
    @Column(nullable = false)
    private Double divertExtraFuelL;
    /** 转场建议得分（分，越低越优）与候选排序快照 */
    @Column(nullable = false)
    private Double divertScore;
    @Column(length = 2000)
    private String alternativesSnapshot;

    /** 系统推荐动作与理由 */
    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private E.RerouteAction recommendedAction;
    @Column(length = 500)
    private String recommendationReason;

    // ---------- 决策执行 ----------
    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private E.RerouteAction executedAction;
    @ManyToOne(fetch = FetchType.LAZY)
    private UserAccount decidedBy;
    private LocalDateTime decidedAt;
    @Column(length = 300)
    private String decisionNote;
    /** 执行后受阻单新的作业窗口开始时间（延期/等待） */
    private LocalDateTime rescheduledStart;

    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true)
    @com.fasterxml.jackson.annotation.JsonManagedReference
    private List<RerouteFarmerNotification> notifications = new ArrayList<>();
}
