package cn.county.agrimach.service.support;

import cn.county.agrimach.domain.enums.E;

import java.util.EnumMap;
import java.util.Map;

/**
 * 县域作业指导价目录（元/亩）+ 空驶费/等待费规则。
 * 油补标准在 SubventionRule 表中维护（可后台配置），此处是农户收费侧口径。
 */
public final class PricingCatalog {

    public record Pricing(double unitPrice, double extraUnitPrice, String note) {}

    private static final Map<E.OperationType, Pricing> TABLE = new EnumMap<>(E.OperationType.class);

    static {
        TABLE.put(E.OperationType.PLOWING,         new Pricing(60, 70, "耕地，超预约面积按追加单价"));
        TABLE.put(E.OperationType.ROTOTILLING,     new Pricing(70, 80, "旋耕"));
        TABLE.put(E.OperationType.HARVESTING,      new Pricing(80, 95, "机收"));
        TABLE.put(E.OperationType.TRANSPLANTING,   new Pricing(90, 105, "插秧"));
        TABLE.put(E.OperationType.PLANT_PROTECTION,new Pricing(25, 30, "植保（按作业幅宽折算亩）"));
        TABLE.put(E.OperationType.STRAW_TREATMENT, new Pricing(50, 60, "秸秆打捆/处理"));
    }

    /** 空驶费 元/km（往返单程计，超 10km 才向农户收取基础空驶费，其余由油补补） */
    public static final double EMPTY_HAUL_RATE_PER_KM = 2.0;
    public static final double EMPTY_HAUL_FREE_KM = 10.0;
    /** 非天气原因等待不向农户收费；天气等待由油补兜底 */
    public static final double WAIT_FEE_PER_HOUR = 0.0;
    /** 重进地返工的机具成本（合作社承担，不计农户）元/亩 */
    public static final double REWORK_COST_PER_MU = 25.0;

    private PricingCatalog() {}

    public static Pricing of(E.OperationType op) {
        return TABLE.get(op);
    }

    /** 泥泞加价系数（作业难度→作业费） */
    public static double mudSurcharge(E.MudLevel mud) {
        return switch (mud) {
            case LIGHT -> 1.0;
            case MEDIUM -> 1.08;
            case HEAVY -> 1.18;
        };
    }

    /** 泥泞降效系数（作业时间乘数） */
    public static double mudSlowdown(E.MudLevel mud) {
        return switch (mud) {
            case LIGHT -> 1.0;
            case MEDIUM -> 1.15;
            case HEAVY -> 1.35;
        };
    }
}
