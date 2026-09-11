package cn.county.agrimach.domain.entity;

import cn.county.agrimach.domain.enums.E;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 作业单 —— 系统核心：
 * 合作社、驾驶员、农户、补贴审核部门共享的同一条作业记录。
 * 任何费用调整（重排重算）都在本表留痕并可追到地块、轨迹与现场验收证据。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "work_order", indexes = {
        @Index(name = "idx_order_farmer", columnList = "farmer_id"),
        @Index(name = "idx_order_coop", columnList = "coop_id"),
        @Index(name = "idx_order_status", columnList = "status")
})
public class WorkOrder {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ---------- 预约信息（农户提交） ----------
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private UserAccount farmer;

    @Column(nullable = false, length = 60)
    private String cropType;

    @Column(nullable = false, length = 60)
    private String plotName;

    @Column(nullable = false, length = 60)
    private String village;

    @Column(nullable = false)
    private Double longitude;

    @Column(nullable = false)
    private Double latitude;

    @Column(nullable = false)
    private Double bookedAreaMu;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private E.OperationType operationType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private E.MudLevel mudLevel;

    @Column(nullable = false)
    private LocalDateTime expectedStart;

    @Column(nullable = false)
    private LocalDateTime expectedEnd;

    @Column(nullable = false)
    private boolean strawRequested;

    @Column(length = 300)
    private String remark;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private E.OrderStatus status = E.OrderStatus.SUBMITTED;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    // ---------- 派机结果 ----------
    @ManyToOne(fetch = FetchType.LAZY)
    private Cooperative coop;

    @ManyToOne(fetch = FetchType.LAZY)
    private Machine machine;

    @ManyToOne(fetch = FetchType.LAZY)
    private Driver driver;

    /** 调度时刻天气（派机建议使用的天气快照） */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private E.WeatherType dispatchWeather;

    /** 合作社驻地到地块道路距离 km */
    private Double roadDistanceKm;

    /** 预计作业时间（分钟，含泥泞/秸秆系数后的纯作业时间） */
    private Integer estimatedWorkMinutes;
    private Integer estimatedEmptyHaulMinutes;

    // ---------- 接单确认（驾驶员） ----------
    private LocalDateTime acceptedAt;
    /** 机具状态确认描述 */
    @Column(length = 300)
    private String machineConditionConfirmed;
    /** 出发油量（L） */
    private Double departureFuelL;
    private LocalDateTime departedAt;
    private LocalDateTime arrivedAt;

    // ---------- 作业与轨迹 ----------
    private LocalDateTime workStartedAt;
    private LocalDateTime workFinishedAt;

    /** 实际作业面积（农户验收确认） */
    private Double actualAreaMu;

    /** 轨迹覆盖面积（轨迹核算，作为验收证据之一） */
    private Double trackAreaMu;

    /** 实际油耗 L（返航/结束油量差额） */
    private Double actualFuelL;

    // ---------- 费用 ----------
    @Column(nullable = false)
    private boolean locked = false;

    /** 作业单价 元/亩 */
    private Double unitPrice;
    private Double extraUnitPrice;
    /** 农户应付总费用 */
    private Double farmerFee;
    /** 空驶费 */
    private Double emptyHaulFee;
    /** 等待费 */
    private Double waitingFee;
    /** 返工费（合作社承担，不计入农户应付，在重算明细中体现） */
    private Double reworkCost;
    /** 油补合计（分段汇总） */
    private Double subsidyTotal;

    /** 费用/时间版本号：每发生一次重排重算 +1，解释每次重排原因 */
    @Column(nullable = false)
    private Integer calcVersion = 0;

    // ---------- 验收 ----------
    private LocalDateTime farmerAcceptedAt;
    @Column(length = 300)
    private String farmerAcceptComment;
    /** 农户验收评分 1-5 */
    private Integer farmerRating;
    /** 农户验收照片编号/引用，逗号分隔（面积争议复核证据之一） */
    @Column(length = 500)
    private String farmerAcceptancePhotoRefs;

    /** 发票号 */
    @Column(length = 40)
    private String invoiceNo;
    private LocalDateTime invoicedAt;

    // ---------- 面积争议复核 ----------
    /** 预约时是否已完成地块边界预确认（诚信风险农户强制） */
    @Column(nullable = false)
    private boolean boundaryPreConfirmed = false;
    @Column(length = 60)
    private String boundaryConfirmRef;
    /** 收费面积已经争议复核调整（结算/补贴同步标记） */
    @Column(nullable = false)
    private boolean areaReviewAdjusted = false;
    /** 经面积争议复核核定的收费面积（为空则以预约/现场实测为准） */
    private Double reviewConfirmedAreaMu;
}
