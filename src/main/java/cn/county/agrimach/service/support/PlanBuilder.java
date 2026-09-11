package cn.county.agrimach.service.support;

import cn.county.agrimach.domain.entity.Machine;
import cn.county.agrimach.domain.entity.WorkException;
import cn.county.agrimach.domain.entity.WorkOrder;
import cn.county.agrimach.domain.enums.E;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 根据作业单 + 全部已登记异常，构造本次核算的“分段清单”。
 * 五类异常如何改写时间/面积/费用，全部集中在此，便于补贴审核时解释每次重排原因：
 *
 * 1) 边界不清 UNCLEAR_BOUNDARY：村干部共同丈量→实测面积；无额外补贴段，仅面积修正
 * 2) 面积超标 AREA_EXCEEDED：超出部分按追加单价计农户费；有效作业面积=实测
 * 3) 雨后无法进地 RAIN_BLOCKED：等待天气段补贴 + 折返/再赴地额外空驶补贴（不重复收农户空驶费）
 * 4) 机具故障 MACHINE_FAULT：故障停机段（低补贴）+ 返工面积段 + 维修窗口；农户费用不担
 * 5) 农户追加 FARMER_ADDON：新增作业类型的有效作业段（追加单价）
 */
@Component
public class PlanBuilder {

    public record BuiltPlan(double effectiveAreaMu, List<CalcPlan.Seg> segments, int totalScheduledMinutes) {}

    public BuiltPlan build(WorkOrder o, Machine machine, List<WorkException> resolvedExceptions) {
        return build(o, machine, resolvedExceptions, null);
    }

    /**
     * @param roadKmOverride 派机建议阶段订单尚未落定距离时，按候选合作社传入；
     *                       派机后重算传 null，使用订单上的道路距离
     */
    public BuiltPlan build(WorkOrder o, Machine machine, List<WorkException> resolvedExceptions,
                           Double roadKmOverride) {
        List<CalcPlan.Seg> segs = new ArrayList<>();
        double area = nz(o.getBookedAreaMu());

        // ---------- 空驶：合作社驻地 → 地块 ----------
        double baseDistance = roadKmOverride != null ? roadKmOverride : nz(o.getRoadDistanceKm());
        int emptyMinutes = minutesForHaul(baseDistance, machine);
        segs.add(new CalcPlan.Seg(E.SegmentType.EMPTY_HAUL, o.getOperationType(), emptyMinutes,
                0, baseDistance, 0, null, "驻地赴地空驶 " + fmt(baseDistance) + "km"));

        int extraHaul = 0;

        // ---------- 已解决异常逐笔改写 ----------
        double reworkArea = 0;
        int weatherWait = 0, faultWait = 0;
        double addonArea = 0;
        E.OperationType addonOp = null;
        Long weatherExcId = null, faultExcId = null, reworkExcId = null, addonExcId = null;

        for (WorkException ex : resolvedExceptions) {
            switch (ex.getType()) {
                case UNCLEAR_BOUNDARY -> {
                    if (ex.getMeasuredAreaMu() != null) area = ex.getMeasuredAreaMu();
                }
                case AREA_EXCEEDED -> {
                    if (ex.getMeasuredAreaMu() != null) area = Math.max(area, ex.getMeasuredAreaMu());
                }
                case RAIN_BLOCKED -> {
                    weatherWait += nzi(ex.getDowntimeMinutes());
                    if (weatherExcId == null) weatherExcId = ex.getId();
                    double extraKm = nz(ex.getExtraEmptyHaulKm());
                    if (extraKm > 0) {
                        int m = minutesForHaul(extraKm, machine);
                        extraHaul += m;
                        segs.add(new CalcPlan.Seg(E.SegmentType.EMPTY_HAUL, o.getOperationType(), m,
                                0, extraKm, 0, ex.getId(), "雨后折返/再赴地额外空驶 " + fmt(extraKm) + "km"));
                    }
                }
                case MACHINE_FAULT -> {
                    faultWait += nzi(ex.getDowntimeMinutes());
                    reworkArea += nz(ex.getReworkAreaMu());
                    if (faultExcId == null) faultExcId = ex.getId();
                    if (nz(ex.getReworkAreaMu()) > 0 && reworkExcId == null) reworkExcId = ex.getId();
                    // 故障换机：新机从驻地赴地的额外空驶
                    double extraKm = nz(ex.getExtraEmptyHaulKm());
                    if (extraKm > 0) {
                        int m = minutesForHaul(extraKm, machine);
                        extraHaul += m;
                        segs.add(new CalcPlan.Seg(E.SegmentType.EMPTY_HAUL, o.getOperationType(), m,
                                0, extraKm, 0, ex.getId(), "故障换机额外空驶 " + fmt(extraKm) + "km"));
                    }
                }
                case FARMER_ADDON -> {
                    addonArea += nz(ex.getAddedAreaMu());
                    addonOp = ex.getAddedOperation() != null ? ex.getAddedOperation() : o.getOperationType();
                    if (addonExcId == null) addonExcId = ex.getId();
                }
            }
        }

        // ---------- 有效作业（主作业） ----------
        int mainMinutes = workMinutes(area, machine, o.getMudLevel());
        segs.add(new CalcPlan.Seg(E.SegmentType.PRODUCTIVE, o.getOperationType(), mainMinutes,
                area, 0, 0, null, "有效作业 " + fmt(area) + " 亩"));

        // ---------- 等待天气 ----------
        if (weatherWait > 0) {
            segs.add(new CalcPlan.Seg(E.SegmentType.WEATHER_WAIT, o.getOperationType(), weatherWait,
                    0, 0, 0, weatherExcId, "雨后无法进地等待"));
        }

        // ---------- 机具故障停机 + 返工 ----------
        if (faultWait > 0) {
            segs.add(new CalcPlan.Seg(E.SegmentType.MACHINE_FAULT, o.getOperationType(), faultWait,
                    0, 0, 0, faultExcId, "机具故障停机（计维修窗口）"));
        }
        if (reworkArea > 0) {
            int rm = workMinutes(reworkArea, machine, o.getMudLevel());
            segs.add(new CalcPlan.Seg(E.SegmentType.REWORK, o.getOperationType(), rm,
                    reworkArea, 0, 0, reworkExcId, "故障后返工 " + fmt(reworkArea) + " 亩"));
        }

        // ---------- 农户临时追加作业（可能是另一作业类型） ----------
        if (addonArea > 0) {
            int am = workMinutes(addonArea, machine, o.getMudLevel());
            segs.add(new CalcPlan.Seg(E.SegmentType.PRODUCTIVE, addonOp, am,
                    addonArea, 0, 0, addonExcId, "农户临时追加 " + addonOp.label + " " + fmt(addonArea) + " 亩"));
        }

        int scheduled = emptyMinutes + extraHaul + mainMinutes + weatherWait + faultWait
                + (reworkArea > 0 ? workMinutes(reworkArea, machine, o.getMudLevel()) : 0)
                + (addonArea > 0 ? workMinutes(addonArea, machine, o.getMudLevel()) : 0);
        return new BuiltPlan(round1(area), segs, scheduled);
    }

    /** 初算时的纯作业时间（派机建议/排期使用） */
    public int initialWorkMinutes(WorkOrder o, Machine machine) {
        return workMinutes(nz(o.getBookedAreaMu()), machine, o.getMudLevel());
    }

    public int minutesForHaul(double km, Machine machine) {
        if (machine == null) return (int) Math.round(km / 25.0 * 60);
        return (int) Math.round(km / nz(machine.getRoadSpeedKmh(), 25.0) * 60);
    }

    private int workMinutes(double area, Machine machine, E.MudLevel mud) {
        double rate = machine != null ? nz(machine.getWorkRateMuPerHour(), 8.0) : 8.0;
        double minutes = area / rate * 60.0 * PricingCatalog.mudSlowdown(mud);
        return (int) Math.round(minutes);
    }

    private static double nz(Double v) { return v == null ? 0 : v; }
    private static int nzi(Integer v) { return v == null ? 0 : v; }
    private static double nz(Double v, double dft) { return v == null ? dft : v; }
    private static String fmt(double v) { return String.format("%.1f", v); }
    private static double round1(double v) { return Math.round(v * 10) / 10.0; }
}
