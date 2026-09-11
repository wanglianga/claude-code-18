package cn.county.agrimach.service;

import cn.county.agrimach.domain.entity.*;
import cn.county.agrimach.domain.enums.E;
import cn.county.agrimach.domain.repo.Repos;
import cn.county.agrimach.service.support.Geo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * 合作社调度视图：关注的是跨村路线（而非单个订单）、机具利用率、
 * 驾驶员疲劳、维修窗口，以及补贴申报材料汇总。
 */
@Service
@RequiredArgsConstructor
public class CoopService {

    private final Repos.OrderRepo orderRepo;
    private final Repos.MachineRepo machineRepo;
    private final Repos.DriverRepo driverRepo;
    private final Repos.MaintenanceRepo maintenanceRepo;
    private final CurrentUser currentUser;

    public record RouteLeg(String fromVillage, String toVillage, String toPlot, double km,
                           String departTime, String arriveTime, Long orderId, String village, String crop, String op) {}
    public record DriverRoute(String driver, String license, int loadMinutes, int fatigueLimit,
                              boolean fatigueRisk, double routeKm, int orderCount, List<RouteLeg> legs) {}
    public record CrossVillageRoute(LocalDate date, int driverCount, int orderCount, double totalKm,
                                    List<String> villages, List<DriverRoute> routes) {}

    /** 跨村路线：按驾驶员串接当日作业单，按出发时间排序，逐段计算村间道路距离 */
    public CrossVillageRoute crossVillageRoutes(Long coopId, LocalDate date) {
        List<WorkOrder> orders = orderRepo.findByCoopIdOrderByCreatedAtDesc(coopId).stream()
                .filter(o -> !o.getStatus().equals(E.OrderStatus.CANCELLED))
                .filter(o -> date == null || sameDay(o.getExpectedStart(), date))
                .toList();

        Map<Driver, List<WorkOrder>> byDriver = new LinkedHashMap<>();
        for (WorkOrder o : orders) {
            if (o.getDriver() == null) continue;
            byDriver.computeIfAbsent(o.getDriver(), k -> new ArrayList<>()).add(o);
        }

        List<DriverRoute> routes = new ArrayList<>();
        Set<String> villages = new TreeSet<>();
        double totalKm = 0;
        int orderCount = 0;

        for (Map.Entry<Driver, List<WorkOrder>> e : byDriver.entrySet()) {
            Driver d = e.getKey();
            List<WorkOrder> chain = e.getValue().stream()
                    .sorted(Comparator.comparing(WorkOrder::getExpectedStart)).toList();
            List<RouteLeg> legs = new ArrayList<>();
            double prevLon = d.getCoop().getLongitude();
            double prevLat = d.getCoop().getLatitude();
            String prevVillage = d.getCoop().getBaseVillage();
            double routeKm = 0;
            int load = 0;
            for (WorkOrder o : chain) {
                double km = Geo.roadKm(prevLon, prevLat, o.getLongitude(), o.getLatitude());
                routeKm += km;
                load += nz(o.getEstimatedWorkMinutes());
                villages.add(o.getVillage());
                legs.add(new RouteLeg(prevVillage, o.getVillage(), o.getPlotName(),
                        Math.round(km * 10) / 10.0,
                        OrderService.DT.format(o.getExpectedStart()),
                        OrderService.DT.format(o.getExpectedStart().plusMinutes(nz(o.getEstimatedEmptyHaulMinutes()))),
                        o.getId(), o.getVillage(), o.getCropType(), o.getOperationType().label));
                prevLon = o.getLongitude();
                prevLat = o.getLatitude();
                prevVillage = o.getVillage();
            }
            // 收车回社
            double back = Geo.roadKm(prevLon, prevLat, d.getCoop().getLongitude(), d.getCoop().getLatitude());
            if (!chain.isEmpty()) {
                routeKm += back;
                legs.add(new RouteLeg(prevVillage, d.getCoop().getBaseVillage(), "返回合作社",
                        Math.round(back * 10) / 10.0, "-", "-", null, d.getCoop().getBaseVillage(), "", "收车"));
            }
            totalKm += routeKm;
            orderCount += chain.size();
            boolean risk = d.getTodayWorkMinutes() + load >= d.getFatigueLimitMinutes() * 0.85;
            routes.add(new DriverRoute(d.getName(), d.getLicenseNo(), d.getTodayWorkMinutes() + load,
                    d.getFatigueLimitMinutes(), risk, Math.round(routeKm * 10) / 10.0, chain.size(), legs));
        }
        routes.sort(Comparator.comparing(DriverRoute::driver));
        return new CrossVillageRoute(date, routes.size(), orderCount,
                Math.round(totalKm * 10) / 10.0, new ArrayList<>(villages), routes);
    }

    public record MachineUtilization(Long machineId, String code, String name, String type,
                                     double hourMeter, int usedMinutes, int capMinutes,
                                     double utilizationRate, boolean maintenanceDue) {}

    /** 机具利用率：当日已排作业分钟 / 日作业上限；并提示到保维修窗口 */
    public List<MachineUtilization> utilization(Long coopId) {
        List<MachineUtilization> out = new ArrayList<>();
        for (Machine m : machineRepo.findByCoopIdAndEnabledTrue(coopId)) {
            int used = orderRepo.findByCoopIdOrderByCreatedAtDesc(coopId).stream()
                    .filter(o -> m.equals(o.getMachine()))
                    .filter(o -> ACTIVE.contains(o.getStatus()) || o.getStatus() == E.OrderStatus.COMPLETED
                            || o.getStatus() == E.OrderStatus.SETTLED || o.getStatus() == E.OrderStatus.INVOICED)
                    .mapToInt(o -> nz(o.getEstimatedWorkMinutes())).sum();
            int cap = m.getDailyCapMinutes();
            boolean due = m.getHourMeter() - m.getLastMaintenanceHour()
                    >= m.getMaintenanceIntervalHours() * 0.9;
            out.add(new MachineUtilization(m.getId(), m.getCode(), m.getName(), m.getType().label,
                    round1(m.getHourMeter()), used, cap,
                    Math.round(Math.min(1.5, used * 100.0 / cap) * 10) / 10.0, due));
        }
        return out;
    }

    public record DriverFatigue(Long driverId, String name, int todayMinutes, int limit,
                                double ratio, String level) {}

    public List<DriverFatigue> fatigue(Long coopId) {
        return driverRepo.findByCoopId(coopId).stream().map(d -> {
            int min = d.getTodayWorkMinutes();
            double ratio = Math.round(min * 1000.0 / d.getFatigueLimitMinutes()) / 10.0;
            String level = ratio >= 100 ? "超限-强制休息" : ratio >= 85 ? "高风险-建议轮换" : "正常";
            return new DriverFatigue(d.getId(), d.getName(), min, d.getFatigueLimitMinutes(), ratio, level);
        }).sorted((a, b) -> Double.compare(b.ratio(), a.ratio())).toList();
    }

    public record MaintenanceWindow(Long id, String machineCode, String description,
                                    String windowStart, String windowEnd, String status,
                                    Double hourMeter, Long orderId, double cost) {}

    /** 维修窗口：已登记维修记录 + 按保养间隔自动预警的窗口 */
    public List<MaintenanceWindow> maintenanceWindows(Long coopId) {
        List<MaintenanceWindow> out = new ArrayList<>();
        for (MaintenanceRecord r : maintenanceRepo.findByMachineCoopIdOrderByWindowStartDesc(coopId)) {
            out.add(new MaintenanceWindow(r.getId(), r.getMachine().getCode(), r.getDescription(),
                    r.getWindowStart() != null ? OrderService.DT.format(r.getWindowStart()) : null,
                    r.getWindowEnd() != null ? OrderService.DT.format(r.getWindowEnd()) : null,
                    r.getStatus().label, r.getHourMeterAtRepair(),
                    r.getWorkOrder() != null ? r.getWorkOrder().getId() : null, r.getCost()));
        }
        for (Machine m : machineRepo.findByCoopIdAndEnabledTrue(coopId)) {
            double dueIn = m.getMaintenanceIntervalHours()
                    - (m.getHourMeter() - m.getLastMaintenanceHour());
            if (dueIn <= m.getMaintenanceIntervalHours() * 0.1) {
                out.add(new MaintenanceWindow(null, m.getCode(),
                        "按保养间隔预警: 距下次保养仅剩 " + Math.round(dueIn * 10) / 10.0 + " 小时",
                        null, null, "计划维修窗口", round1(m.getHourMeter()), null, 0));
            }
        }
        return out;
    }

    private static final List<E.OrderStatus> ACTIVE = List.of(
            E.OrderStatus.DISPATCHED, E.OrderStatus.ACCEPTED, E.OrderStatus.EN_ROUTE,
            E.OrderStatus.ARRIVED, E.OrderStatus.IN_PROGRESS, E.OrderStatus.INTERRUPTED);

    private static boolean sameDay(java.time.LocalDateTime t, LocalDate d) {
        return t != null && t.toLocalDate().equals(d);
    }
    private static int nz(Integer v) { return v == null ? 0 : v; }
    private static double round1(double v) { return Math.round(v * 10) / 10.0; }
}
