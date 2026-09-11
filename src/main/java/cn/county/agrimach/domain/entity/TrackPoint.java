package cn.county.agrimach.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** 实际作业轨迹点（轨迹核算面积的原始证据） */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "track_point", indexes = @Index(name = "idx_track_order", columnList = "work_order_id"))
public class TrackPoint {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private WorkOrder workOrder;

    @Column(nullable = false)
    private Integer seq;

    @Column(nullable = false)
    private LocalDateTime pointTime;

    @Column(nullable = false)
    private Double longitude;

    @Column(nullable = false)
    private Double latitude;

    /** 瞬时速度 km/h，用于区分空驶轨迹与作业轨迹 */
    private Double speedKmh;

    /** 是否有效作业点（作业轨迹）vs 转场/空驶点 */
    @Column(nullable = false)
    private boolean working = true;

    @Column(length = 200)
    private String note;
}
