package cn.county.agrimach.service.support;

import cn.county.agrimach.domain.enums.E;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次（重）核算的完整结果：作业时间、费用、油补、分段。
 * 初算与五类异常重算共用同一输出结构，保证每次调整口径一致、可逐版对照。
 */
public class CalcPlan {

    /** 分段输入（引擎据此匹配补贴规则） */
    public record Seg(E.SegmentType type, E.OperationType op, double minutes,
                      double areaMu, double distanceKm, double fuelL, Long exceptionId, String note) {}

    /** 分段核算结果 */
    public record SegResult(E.SegmentType type, E.OperationType op, int minutes, double areaMu,
                            double distanceKm, double fuelL, double rate, String basis,
                            double subsidy, Long exceptionId, String note) {}

    public double effectiveAreaMu;
    public int emptyHaulMinutes;
    public int workMinutes;
    public int weatherWaitMinutes;
    public int reworkMinutes;
    public int faultMinutes;
    public double totalFuelL;

    public double workFee;
    public double emptyHaulFee;
    public double waitingFee;
    public double reworkCost;
    public double farmerFee;
    public double subsidyTotal;

    /** 分段补贴小计（空驶/有效作业/等待天气/返工/机具故障） */
    public final Map<E.SegmentType, Double> subsidyByType = new LinkedHashMap<>();
    public final List<SegResult> segments = new ArrayList<>();
    public final List<String> ruleWarnings = new ArrayList<>();

    public CalcPlan() {
        for (E.SegmentType t : E.SegmentType.values()) subsidyByType.put(t, 0.0);
    }
}
