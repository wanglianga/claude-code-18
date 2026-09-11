package cn.county.agrimach.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 农机专业合作社（调度主体，坐标用于跨村道路距离估算） */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "cooperative")
public class Cooperative {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 80)
    private String name;

    /** 驻地所在村/镇 */
    @Column(nullable = false, length = 60)
    private String baseVillage;

    @Column(nullable = false)
    private Double longitude;

    @Column(nullable = false)
    private Double latitude;

    @Column(length = 40)
    private String contactName;

    @Column(length = 20)
    private String contactPhone;

    public Cooperative(String name, String baseVillage, Double longitude, Double latitude,
                       String contactName, String contactPhone) {
        this.name = name;
        this.baseVillage = baseVillage;
        this.longitude = longitude;
        this.latitude = latitude;
        this.contactName = contactName;
        this.contactPhone = contactPhone;
    }
}
