package cn.county.agrimach.service.support;

import cn.county.agrimach.domain.entity.Machine;
import cn.county.agrimach.domain.entity.SubventionRule;
import cn.county.agrimach.domain.enums.E;
import cn.county.agrimach.domain.repo.Repos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 核算引擎（初算与五类异常重算共用）：
 * 入参是“分段清单”，出参是时间/费用/油补/分段明细 —— 把补贴严格锚定到
 * 空驶、有效作业、等待天气、返工、机具故障五类实际服务上。
 */
@Component
@RequiredArgsConstructor
public class CalcEngine {

    private final Repos.RuleRepo ruleRepo;

    public CalcPlan calculate(E.OperationType mainOp, E.MudLevel mud, Machine machine,
                              double effectiveAreaMu, List<CalcPlan.Seg> segments) {
        CalcPlan plan = new CalcPlan();
        plan.effectiveAreaMu = round2(effectiveAreaMu);

        double productiveMainArea = 0, productiveAddonArea = 0;
        int productiveCount = 0;

        for (CalcPlan.Seg seg : segments) {
            double hours = seg.minutes() / 60.0;
            double fuel = seg.fuelL();
            if (fuel <= 0 && machine != null) {
                fuel = estimateFuel(seg.type(), hours, machine.getHourlyFuelL());
            }

            SubventionRule rule = ruleRepo
                    .findByOperationTypeAndSegmentType(seg.op(), seg.type())
                    .orElse(null);
            double rate = 0, factor = 1, subsidy = 0;
            String basis = "HOUR";
            if (rule != null) {
                rate = rule.getRate();
                factor = rule.getFactor() == null ? 1.0 : rule.getFactor();
                basis = rule.getBasis();
                if (rule.isPayable()) {
                    double qty = switch (basis) {
                        case "MU" -> seg.areaMu();
                        case "KM" -> seg.distanceKm();
                        default -> hours;
                    };
                    subsidy = qty * rate * factor;
                }
            } else {
                plan.ruleWarnings.add("缺少补贴规则: " + seg.op() + "/" + seg.type());
            }
            plan.subsidyByType.merge(seg.type(), round2(subsidy), Double::sum);

            // 农户费用侧：首段有效作业按主作业单价，其余按追加作业单价
            if (seg.type() == E.SegmentType.PRODUCTIVE) {
                PricingCatalog.Pricing p = PricingCatalog.of(seg.op());
                double surcharge = PricingCatalog.mudSurcharge(mud);
                if (productiveCount == 0 && seg.op() == mainOp) {
                    productiveMainArea += seg.areaMu();
                    plan.workFee += seg.areaMu() * p.unitPrice() * surcharge;
                } else {
                    productiveAddonArea += seg.areaMu();
                    plan.workFee += seg.areaMu() * p.extraUnitPrice() * surcharge;
                }
                productiveCount++;
                plan.workMinutes += (int) Math.round(seg.minutes());
            } else if (seg.type() == E.SegmentType.EMPTY_HAUL) {
                plan.emptyHaulMinutes += (int) Math.round(seg.minutes());
                // 仅常规赴地空驶（无异常关联）计入农户空驶费，异常折返不重复向农户收费
                if (seg.exceptionId() == null) {
                    plan.emptyHaulFee += Math.max(0, seg.distanceKm() - PricingCatalog.EMPTY_HAUL_FREE_KM)
                            * PricingCatalog.EMPTY_HAUL_RATE_PER_KM;
                }
            } else if (seg.type() == E.SegmentType.WEATHER_WAIT) {
                plan.weatherWaitMinutes += (int) Math.round(seg.minutes());
            } else if (seg.type() == E.SegmentType.REWORK) {
                plan.reworkMinutes += (int) Math.round(seg.minutes());
                plan.reworkCost += seg.areaMu() * PricingCatalog.REWORK_COST_PER_MU;
            } else if (seg.type() == E.SegmentType.MACHINE_FAULT) {
                plan.faultMinutes += (int) Math.round(seg.minutes());
            }

            plan.totalFuelL += fuel;
            plan.segments.add(new CalcPlan.SegResult(seg.type(), seg.op(),
                    (int) Math.round(seg.minutes()), round2(seg.areaMu()), round2(seg.distanceKm()),
                    round2(fuel), round2(rate), basis, round2(subsidy), seg.exceptionId(), seg.note()));
        }

        plan.waitingFee = PricingCatalog.WAIT_FEE_PER_HOUR * (plan.weatherWaitMinutes / 60.0);
        plan.workFee = round2(plan.workFee);
        plan.emptyHaulFee = round2(plan.emptyHaulFee);
        plan.waitingFee = round2(plan.waitingFee);
        plan.reworkCost = round2(plan.reworkCost);
        plan.farmerFee = round2(plan.workFee + plan.emptyHaulFee + plan.waitingFee);
        plan.subsidyTotal = round2(plan.subsidyByType.values().stream().mapToDouble(Double::doubleValue).sum());
        plan.totalFuelL = round2(plan.totalFuelL);
        return plan;
    }

    /** 不同分段的发动机负载不同 → 油耗不同，避免补贴油耗与实际工况脱节 */
    private double estimateFuel(E.SegmentType type, double hours, double hourlyFuelL) {
        double loadFactor = switch (type) {
            case EMPTY_HAUL -> 0.45;   // 道路行驶
            case PRODUCTIVE -> 1.0;    // 满负荷作业
            case REWORK -> 1.05;       // 返工地块工况更差
            case WEATHER_WAIT -> 0.10; // 怠速等待
            case MACHINE_FAULT -> 0.08;// 故障停机
        };
        return round2(hourlyFuelL * loadFactor * hours);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
