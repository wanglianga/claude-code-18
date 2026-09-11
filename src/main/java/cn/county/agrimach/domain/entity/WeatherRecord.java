package cn.county.agrimach.domain.entity;

import cn.county.agrimach.domain.enums.E;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 天气预报/实况记录（按村 + 日期）：
 * 派机建议据此调整出发时间与评分；雨后无法进地异常与此对照解释重排原因。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "weather_record",
        uniqueConstraints = @UniqueConstraint(columnNames = {"village", "forecast_date"}))
public class WeatherRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 60)
    private String village;

    /** yyyy-MM-dd */
    @Column(name = "forecast_date", nullable = false, length = 10)
    private String date;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private E.WeatherType weather;

    /** 预计降水量 mm（演示用，影响进地可行性判断） */
    @Column(nullable = false)
    private Double rainfallMm = 0.0;

    @Column(length = 200)
    private String advisory;
}
