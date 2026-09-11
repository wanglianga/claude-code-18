package cn.county.agrimach.service;

import cn.county.agrimach.domain.entity.*;
import cn.county.agrimach.domain.enums.E;
import cn.county.agrimach.domain.repo.Repos;
import cn.county.agrimach.service.support.Geo;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 雨后作业窗口重排：
 * 某村因降水/土壤湿度超过机具进地阈值无法进机时，按
 * 土壤湿度、作物成熟紧迫度、农机当前位置、其他村可作业预约重新排序，
 * 合作社可“先转去可作业地块”或“原地等待晾墒”；
 * 决策联动空驶油耗（转场绕行里程计入空驶段补贴）、农户满意度
 * （通知响应：接受延期/要求换机/取消）与补贴有效作业比例，
 * 并最终计入合作社调度评分。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RerouteService {

    private final Repos.OrderRepo orderRepo;
    private final Repos.MachineRepo machineRepo;
    private final Repos.DriverRepo driverRepo;
    private final Repos.WeatherRepo weatherRepo;
    private final Repos.ReroutePlanRepo planRepo;
    private final Repos.RerouteNotificationRepo notificationRepo;
    private final Repos.ExceptionRepo exceptionRepo;
    private final Repos.SignoffRepo signoffRepo;
    private final Repos.ClaimRepo claimRepo;
    private final OrderService orderService;
    private final CurrentUser currentUser;
    private final ObjectMapper objectMapper;

    private static final List<E.OrderStatus> REROUTABLE = List.of(
            E.OrderStatus.DISPATCHED, E.OrderStatus.ACCEPTED, E.OrderStatus.EN_ROUTE,
            E.OrderStatus.ARRIVED, E.OrderStatus.IN_PROGRESS, E.OrderStatus.INTERRUPTED);
    private static final List<E.OrderStatus> AT_PLOT = List.of(
            E.OrderStatus.EN_ROUTE, E.OrderStatus.ARRIVED,
            E.OrderStatus.IN_PROGRESS, E.OrderStatus.INTERRUPTED);

    public record BlockedOrder(Long orderId, String village, String plot, String machineCode,
                               Double soilMoisturePct, Double rainfallMm, Double thresholdPct,
                               String status, Integer matureDaysLeft) {}
    public record Candidate(Long orderId, String village, String plot, double distanceKm,
                            int matureDaysLeft, double score, String reason) {}

    // ============================== 受阻扫描 ==============================

    @Transactional
    public List<BlockedOrder> scanBlocked(Long coopId, LocalDate date) {
        List<BlockedOrder> out = new ArrayList<>();
        for (WorkOrder o : orderRepo.findByCoopIdOrderByCreatedAtDesc(coopId)) {
            if (!REROUTABLE.contains(o.getStatus()) || o.getMachine() == null) continue;
            if (date != null && !o.getExpectedStart().toLocalDate().equals(date)) continue;
            WeatherRecord w = weatherRepo
                    .findByVillageAndDate(o.getVillage(), o.getExpectedStart().toLocalDate().toString())
                    .orElse(null);
            if (w == null) continue;
            double thr = nz(o.getMachine().getMaxSoilMoisturePct(), 82.0);
            double moisture = nz(w.getSoilMoisturePct());
            if (w.isMachineAccessBlocked() || moisture > thr) {
                out.add(new BlockedOrder(o.getId(), o.getVillage(), o.getPlotName(),
                        o.getMachine().getCode(), moisture, w.getRainfallMm(), thr,
                        o.getStatus().label, o.getMatureDaysLeft()));
            }
        }
        return out;
    }

    // ============================== 生成重排计划 ==============================

    @Transactional
    public ReroutePlan buildPlan(Long blockedOrderId) {
        WorkOrder blocked = mustOrder(blockedOrderId);
        ensureCoop(blocked);
        if (!REROUTABLE.contains(blocked.getStatus())) {
            throw bad("订单当前状态「" + blocked.getStatus().label + "」不能做雨后重排");
        }
        LocalDate d = blocked.getExpectedStart().toLocalDate();
        WeatherRecord w = weatherRepo.findByVillageAndDate(blocked.getVillage(), d.toString())
                .orElseThrow(() -> bad("该村当日无天气/土壤记录，无法判定进机条件"));
        double thr = nz(blocked.getMachine().getMaxSoilMoisturePct(), 82.0);
        double moisture = nz(w.getSoilMoisturePct());
        if (!w.isMachineAccessBlocked() && moisture <= thr) {
            throw bad("当前土壤湿度 " + moisture + "% 未超过机具阈值 " + thr + "%，无需重排");
        }

        Machine m = blocked.getMachine();
        // 农机当前位置：已出发即在受阻地块，否则在合作社驻地
        boolean atPlot = AT_PLOT.contains(blocked.getStatus());
        double fromLon = atPlot ? blocked.getLongitude() : blocked.getCoop().getLongitude();
        double fromLat = atPlot ? blocked.getLatitude() : blocked.getCoop().getLatitude();

        // 其他村可作业预约：未分派 + 同机具能力 + 当日该村可进地 + 湿度不超阈值
        List<Candidate> candidates = new ArrayList<>();
        for (WorkOrder alt : orderRepo.findByStatusIn(List.of(E.OrderStatus.SUBMITTED))) {
            if (alt.getId().equals(blocked.getId())) continue;
            if (!m.supports(alt.getOperationType())) continue;
            if (alt.isStrawRequested() && !m.supports(E.OperationType.STRAW_TREATMENT)) continue;
            WeatherRecord wa = weatherRepo
                    .findByVillageAndDate(alt.getVillage(), alt.getExpectedStart().toLocalDate().toString())
                    .orElse(null);
            if (wa != null && (wa.isMachineAccessBlocked() || nz(wa.getSoilMoisturePct()) > thr)) continue;
            double km = Geo.roadKm(fromLon, fromLat, alt.getLongitude(), alt.getLatitude());
            double score = Math.round((km * 1.2 + alt.getMatureDaysLeft() * 3.0) * 10) / 10.0;
            candidates.add(new Candidate(alt.getId(), alt.getVillage(), alt.getPlotName(),
                    Math.round(km * 100) / 100.0, alt.getMatureDaysLeft(), score,
                    "距机具" + Math.round(km * 10) / 10.0 + "km; 作物" + alt.getMatureDaysLeft()
                            + "天后到期，紧迫加分" + alt.getMatureDaysLeft() * 3));
        }
        candidates.sort(Comparator.comparingDouble(Candidate::score));

        // 最早可进地日期（按天气预报/湿度序列）
        LocalDate next = d.plusDays(1);
        for (int i = 1; i <= 7; i++) {
            LocalDate cand = d.plusDays(i);
            WeatherRecord wi = weatherRepo.findByVillageAndDate(blocked.getVillage(), cand.toString()).orElse(null);
            if (wi == null || (!wi.isMachineAccessBlocked() && nz(wi.getSoilMoisturePct()) <= thr)) {
                next = cand;
                break;
            }
        }
        LocalDateTime rescheduled = next.atTime(blocked.getExpectedStart().toLocalTime());
        long deltaMin = java.time.Duration.between(LocalDateTime.now(), rescheduled).toMinutes();
        // 等待补贴按当日晾墒等待计（上限8小时），跨天部分只做窗口顺延
        int waitMinutes = (int) Math.max(60, Math.min(480, deltaMin));
        int delayDays = (int) java.time.temporal.ChronoUnit.DAYS.between(d, next);

        ReroutePlan plan = new ReroutePlan();
        plan.setBlockedOrder(blocked);
        plan.setCoop(blocked.getCoop());
        plan.setMachine(m);
        plan.setBlockedSoilMoisturePct(moisture);
        plan.setBlockedRainfallMm(nz(w.getRainfallMm()));
        plan.setNextAccessibleDate(next.toString());
        plan.setProposedWaitMinutes(waitMinutes);
        plan.setWaitSatisfactionImpact(-2 * Math.max(1, delayDays));
        try {
            plan.setAlternativesSnapshot(objectMapper.writeValueAsString(candidates));
        } catch (Exception e) {
            plan.setAlternativesSnapshot(candidates.toString());
        }

        if (!candidates.isEmpty()) {
            Candidate best = candidates.get(0);
            WorkOrder altOrder = orderRepo.findById(best.orderId()).orElseThrow();
            double extraKm;
            if (atPlot) {
                // 受阻地块 → 候选地块 → 完工后返回受阻地块
                extraKm = Math.round(2 * best.distanceKm() * 100) / 100.0;
            } else {
                // 驻地 → 候选 → 受阻，相对于原计划 驻地 → 受阻 的新增里程
                double normal = Geo.roadKm(blocked.getCoop().getLongitude(), blocked.getCoop().getLatitude(),
                        blocked.getLongitude(), blocked.getLatitude());
                extraKm = Math.round(Math.max(0,
                        Geo.roadKm(blocked.getCoop().getLongitude(), blocked.getCoop().getLatitude(),
                                altOrder.getLongitude(), altOrder.getLatitude())
                                + best.distanceKm() - normal) * 100) / 100.0;
            }
            int extraMin = (int) Math.round(extraKm / nz(m.getRoadSpeedKmh(), 25.0) * 60);
            double extraFuel = Math.round(extraKm * nz(m.getHourlyFuelL(), 18.0) * 0.45
                    / nz(m.getRoadSpeedKmh(), 25.0) * 100) / 100.0;
            plan.setBestAlternativeOrder(altOrder);
            plan.setDivertExtraKm(extraKm);
            plan.setDivertExtraMinutes(extraMin);
            plan.setDivertExtraFuelL(extraFuel);
            plan.setDivertScore(best.score());
            boolean recommendDivert = extraMin < waitMinutes;
            plan.setRecommendedAction(recommendDivert ? E.RerouteAction.DIVERT : E.RerouteAction.WAIT);
            plan.setRecommendationReason(recommendDivert
                    ? "转场" + best.village() + "仅增加空驶" + extraKm + "km/" + extraMin
                      + "分钟（油耗约+" + extraFuel + "L），短于原地等待" + waitMinutes + "分钟，建议先转场"
                    : "最近可作业地块绕行" + extraKm + "km 仍长于等待窗口，建议原地等待至 " + next);
        } else {
            plan.setDivertExtraKm(0.0);
            plan.setDivertExtraMinutes(0);
            plan.setDivertExtraFuelL(0.0);
            plan.setDivertScore(999.0);
            plan.setRecommendedAction(E.RerouteAction.WAIT);
            plan.setRecommendationReason("无其他村可作业候选地块，建议原地等待晾墒至 " + next);
        }
        return planRepo.save(plan);
    }

    // ============================== 合作社决策 ==============================

    public record DecideReq(E.RerouteAction action, String note) {}

    @Transactional
    public ReroutePlan decide(Long planId, DecideReq r) {
        ReroutePlan plan = mustPlan(planId);
        if (plan.getStatus() != E.ReroutePlanStatus.PROPOSED) throw bad("计划已执行");
        WorkOrder blocked = plan.getBlockedOrder();
        LocalDateTime rescheduled = LocalDate.parse(plan.getNextAccessibleDate())
                .atTime(blocked.getExpectedStart().toLocalTime());
        plan.setExecutedAction(r.action());
        plan.setDecidedBy(currentUser.get());
        plan.setDecidedAt(LocalDateTime.now());
        plan.setDecisionNote(r.note());
        plan.setRescheduledStart(rescheduled);

        if (r.action() == E.RerouteAction.DIVERT) {
            WorkOrder alt = plan.getBestAlternativeOrder();
            if (alt == null) throw bad("无候选地块，不能转场");
            executeDivert(plan, blocked, alt, rescheduled);
            plan.setStatus(E.ReroutePlanStatus.EXECUTED_DIVERT);
        } else {
            executeWait(plan, blocked, rescheduled);
            plan.setStatus(E.ReroutePlanStatus.EXECUTED_WAIT);
        }

        // 通知受影响农户
        notifyBlockedFarmer(plan, blocked, rescheduled, r.action());
        if (r.action() == E.RerouteAction.DIVERT && plan.getBestAlternativeOrder() != null) {
            notifyAlternativeFarmer(plan, plan.getBestAlternativeOrder());
        }
        return planRepo.save(plan);
    }

    private void executeDivert(ReroutePlan plan, WorkOrder blocked, WorkOrder alt, LocalDateTime rescheduled) {
        // 受阻单：顺延窗口 + 雨后重排异常（带转场额外空驶里程），重算油补分段
        blocked.setExpectedStart(rescheduled);
        blocked.setExpectedEnd(rescheduled.plusMinutes(
                orderService.estimatedWorkMinutes(blocked)));
        WorkException ex = newResolvedException(blocked, E.ExceptionType.RAIN_BLOCKED,
                "雨后无法进地，转场至「" + alt.getVillage() + alt.getPlotName() + "」作业后返回",
                0, plan.getDivertExtraKm());
        orderService.applyCalc(blocked, ex,
                "雨后重排-转场：额外空驶" + plan.getDivertExtraKm() + "km（油耗约+"
                        + plan.getDivertExtraFuelL() + "L），窗口顺延至 " + plan.getNextAccessibleDate(),
                "COOP", false);

        // 候选地块：即时分派给同一机具组（从机具当前位置赴地）
        alt.setCoop(blocked.getCoop());
        alt.setMachine(blocked.getMachine());
        alt.setDriver(blocked.getDriver());
        alt.setDispatchWeather(E.WeatherType.SUNNY);
        alt.setRoadDistanceKm(plan.getDivertExtraKm() > 0 && AT_PLOT.contains(blocked.getStatus())
                ? plan.getDivertExtraKm() / 2.0
                : Geo.roadKm(blocked.getCoop().getLongitude(), blocked.getCoop().getLatitude(),
                        alt.getLongitude(), alt.getLatitude()));
        alt.setStatus(E.OrderStatus.DISPATCHED);
        orderService.applyCalc(alt, null, "雨后重排-转场派机（其他村可作业地块优先）", "COOP", true);
        Signoff s = new Signoff(alt, E.SignoffType.COOP_DISPATCH);
        s.setStatus(E.SignoffStatus.APPROVED);
        s.setSigner(currentUser.get());
        s.setOpinion("雨后转场优先调度");
        s.setSignedAt(LocalDateTime.now());
        signoffRepo.save(s);
    }

    private void executeWait(ReroutePlan plan, WorkOrder blocked, LocalDateTime rescheduled) {
        blocked.setExpectedStart(rescheduled);
        blocked.setExpectedEnd(rescheduled.plusMinutes(orderService.estimatedWorkMinutes(blocked)));
        WorkException ex = newResolvedException(blocked, E.ExceptionType.RAIN_BLOCKED,
                "土壤湿度" + plan.getBlockedSoilMoisturePct() + "%超进地阈值，原地等待晾墒",
                plan.getProposedWaitMinutes(), 0.0);
        orderService.applyCalc(blocked, ex,
                "雨后重排-等待：等待天气" + plan.getProposedWaitMinutes()
                        + "分钟，窗口顺延至 " + plan.getNextAccessibleDate(),
                "COOP", false);
    }

    private WorkException newResolvedException(WorkOrder o, E.ExceptionType type, String resolution,
                                               int downtime, double extraKm) {
        WorkException ex = new WorkException();
        ex.setWorkOrder(o);
        ex.setType(type);
        ex.setReportedBy(currentUser.get());
        ex.setDescription(resolution);
        ex.setEvidence("天气/土壤墒情记录+重排计划");
        ex.setDowntimeMinutes(downtime);
        ex.setExtraEmptyHaulKm(extraKm);
        ex.setCalcVersionAtReport(o.getCalcVersion());
        ex.setState(E.ExceptionState.RESOLVED);
        ex.setResolution(resolution);
        ex.setResolvedAt(LocalDateTime.now());
        return exceptionRepo.save(ex);
    }

    private void notifyBlockedFarmer(ReroutePlan plan, WorkOrder o, LocalDateTime when, E.RerouteAction action) {
        RerouteFarmerNotification n = new RerouteFarmerNotification();
        n.setPlan(plan);
        n.setWorkOrder(o);
        n.setFarmer(o.getFarmer());
        n.setContent(action == E.RerouteAction.DIVERT
                ? "因雨后您村地块暂不能进机（土壤湿度" + plan.getBlockedSoilMoisturePct()
                  + "%），合作社已安排机具先转其他村作业，您的作业顺延至 " + when
                  + "，转场绕行空驶不向您加收费用。"
                : "因雨后土壤过湿无法进机，机具原地等待晾墒，您的作业顺延至 " + when + "。");
        n.setNotifyStatus(E.NotificationStatus.SENT);
        n.setSentAt(LocalDateTime.now());
        // 等待天然影响满意度；转场不影响（窗口同样顺延但机具不空等）
        n.setSatisfactionImpact(action == E.RerouteAction.WAIT ? plan.getWaitSatisfactionImpact() : 0);
        plan.getNotifications().add(notificationRepo.save(n));
    }

    private void notifyAlternativeFarmer(ReroutePlan plan, WorkOrder alt) {
        RerouteFarmerNotification n = new RerouteFarmerNotification();
        n.setPlan(plan);
        n.setWorkOrder(alt);
        n.setFarmer(alt.getFarmer());
        n.setContent("雨后跨村调度：机具将优先到您村地块作业（由重排转场安排）。");
        n.setNotifyStatus(E.NotificationStatus.SENT);
        n.setSentAt(LocalDateTime.now());
        n.setSatisfactionImpact(2);
        plan.getNotifications().add(notificationRepo.save(n));
    }

    // ============================== 农户响应 ==============================

    public record RespondReq(E.FarmerResponse response, String note) {}

    @Transactional
    public RerouteFarmerNotification respond(Long notificationId, RespondReq r) {
        RerouteFarmerNotification n = mustNotification(notificationId);
        if (!n.getFarmer().getId().equals(currentUser.get().getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅通知农户本人可回复");
        }
        n.setResponse(r.response());
        n.setResponseNote(r.note());
        n.setRespondedAt(LocalDateTime.now());
        n.setNotifyStatus(E.NotificationStatus.RESPONDED);
        // 在通知初始影响上叠加农户响应影响
        int add = switch (r.response()) {
            case ACCEPT_DELAY -> -2;
            case NEED_MACHINE_CHANGE -> -10;
            case CANCEL_ORDER -> -15;
            default -> 0;
        };
        n.setSatisfactionImpact(n.getSatisfactionImpact() + add);
        return notificationRepo.save(n);
    }

    /** 合作社处理“要求换机具”：跨社选择一台进地阈值满足目标日条件的同类机具重新派机 */
    @Transactional
    public RerouteFarmerNotification handleMachineChange(Long notificationId) {
        RerouteFarmerNotification n = mustNotification(notificationId);
        if (n.getResponse() != E.FarmerResponse.NEED_MACHINE_CHANGE || n.isHandled()) {
            throw bad("该通知不处于待换机处置状态");
        }
        WorkOrder o = n.getWorkOrder();
        Machine old = o.getMachine();
        Machine picked = null;
        Driver pickedDriver = null;
        double bestKm = Double.MAX_VALUE;
        LocalDate targetDate = o.getExpectedStart().toLocalDate();
        WeatherRecord w = weatherRepo.findByVillageAndDate(o.getVillage(), targetDate.toString()).orElse(null);
        for (Machine m : machineRepo.findByEnabledTrue()) {
            if (old != null && m.getId().equals(old.getId())) continue;
            if (!m.supports(o.getOperationType())) continue;
            if (w != null && nz(w.getSoilMoisturePct()) > nz(m.getMaxSoilMoisturePct(), 82.0)) continue;
            Driver d = driverRepo.findByCoopId(m.getCoop().getId()).stream()
                    .filter(x -> x.qualifiedFor(m.getType().name()))
                    .min(Comparator.comparingInt(Driver::getTodayWorkMinutes)).orElse(null);
            if (d == null) continue;
            double km = Geo.roadKm(m.getCoop().getLongitude(), m.getCoop().getLatitude(),
                    o.getLongitude(), o.getLatitude());
            if (km < bestKm) { bestKm = km; picked = m; pickedDriver = d; }
        }
        if (picked == null) throw bad("暂无可在目标日进地的替代机具");
        o.setCoop(picked.getCoop());
        o.setMachine(picked);
        o.setDriver(pickedDriver);
        o.setRoadDistanceKm(Math.round(bestKm * 100) / 100.0);
        orderService.applyCalc(o, null,
                "农户要求换机具：改派" + picked.getCoop().getName() + " " + picked.getCode()
                        + "（进地阈值" + picked.getMaxSoilMoisturePct() + "%）", "COOP", false);
        n.setHandled(true);
        return notificationRepo.save(n);
    }

    /** 合作社确认取消作业 */
    @Transactional
    public RerouteFarmerNotification handleCancel(Long notificationId) {
        RerouteFarmerNotification n = mustNotification(notificationId);
        if (n.getResponse() != E.FarmerResponse.CANCEL_ORDER || n.isHandled()) {
            throw bad("该通知不处于待取消处置状态");
        }
        WorkOrder o = n.getWorkOrder();
        o.setStatus(E.OrderStatus.CANCELLED);
        n.getPlan().setStatus(E.ReroutePlanStatus.CANCELLED);
        n.setHandled(true);
        return notificationRepo.save(n);
    }

    // ============================== 调度评分 ==============================

    public record ScoreCard(Long coopId, int planCount, int divertCount, int waitCount,
                            int acceptDelay, int machineChange, int cancel, int pendingResponse,
                            int satisfactionPoints, double avgProductiveRatioPct,
                            double dispatchScore, String grade, List<String> breakdown) {}

    @Transactional
    public ScoreCard dispatchScore(Long coopId) {
        List<ReroutePlan> plans = planRepo.findByCoopIdOrderByCreatedAtDesc(coopId);
        int divert = 0, wait = 0, accept = 0, change = 0, cancel = 0, pending = 0, sat = 0;
        for (ReroutePlan p : plans) {
            // 按已执行动作统计（等待后取消的计划仍计一次等待决策）
            if (p.getExecutedAction() == E.RerouteAction.DIVERT) divert++;
            if (p.getExecutedAction() == E.RerouteAction.WAIT) wait++;
            for (RerouteFarmerNotification n : p.getNotifications()) {
                sat += n.getSatisfactionImpact();
                if (n.getResponse() == E.FarmerResponse.ACCEPT_DELAY) accept++;
                if (n.getResponse() == E.FarmerResponse.NEED_MACHINE_CHANGE) change++;
                if (n.getResponse() == E.FarmerResponse.CANCEL_ORDER) cancel++;
                if (n.getResponse() == E.FarmerResponse.PENDING && n.getNotifyStatus() == E.NotificationStatus.SENT) pending++;
            }
        }
        // 有效作业比例取该社全部申报单均值（补贴申报中的有效作业占比）
        List<Double> ratios = claimRepo.findByCoopIdOrderByCreatedAtDesc(coopId).stream()
                .map(SubsidyClaim::getProductiveRatioPct).filter(Objects::nonNull).toList();
        double avgRatio = ratios.isEmpty() ? 0 :
                Math.round(ratios.stream().mapToDouble(Double::doubleValue).average().orElse(0) * 10) / 10.0;

        double score = 100.0 + sat - change * 6.0 - cancel * 10.0 - pending * 1.5
                + (avgRatio > 0 ? (avgRatio - 80.0) * 0.3 : 0);
        score = Math.round(Math.max(0, Math.min(100, score)) * 10) / 10.0;
        String grade = score >= 90 ? "优秀" : score >= 75 ? "良好" : score >= 60 ? "合格" : "待改进";
        List<String> bd = new ArrayList<>(List.of(
                "基础分100",
                "重排通知满意度合计 " + sat + " 分（接受延期-2/次，换机-10/次，取消-15/次，等待-2/天，转场受益+2）",
                "要求换机 " + change + " 起 × -6",
                "取消作业 " + cancel + " 起 × -10",
                "未回复通知 " + pending + " 条 × -1.5",
                "申报有效作业比例均值 " + avgRatio + "%（高于80%部分×0.3）"));
        return new ScoreCard(coopId, plans.size(), divert, wait, accept, change, cancel, pending,
                sat, avgRatio, score, grade, bd);
    }

    @Transactional
    public List<RerouteFarmerNotification> myNotifications() {
        return notificationRepo.findByFarmerIdOrderBySentAtDesc(currentUser.get().getId());
    }

    @Transactional
    public List<ReroutePlan> listPlans(Long coopId) {
        return planRepo.findByCoopIdOrderByCreatedAtDesc(coopId);
    }

    // ============================== 天气/墒情录入 ==============================

    public record WeatherUpsertReq(String village, String date, E.WeatherType weather,
                                   Double rainfallMm, Double soilMoisturePct,
                                   Boolean machineAccessBlocked, String advisory) {}

    @Transactional
    public WeatherRecord upsertWeather(WeatherUpsertReq r) {
        WeatherRecord wr = weatherRepo.findByVillageAndDate(r.village(), r.date()).orElseGet(() -> {
            WeatherRecord x = new WeatherRecord();
            x.setVillage(r.village());
            x.setDate(r.date());
            return x;
        });
        if (r.weather() != null) wr.setWeather(r.weather());
        if (r.rainfallMm() != null) wr.setRainfallMm(r.rainfallMm());
        if (r.soilMoisturePct() != null) wr.setSoilMoisturePct(r.soilMoisturePct());
        if (r.machineAccessBlocked() != null) wr.setMachineAccessBlocked(r.machineAccessBlocked());
        if (r.advisory() != null) wr.setAdvisory(r.advisory());
        return weatherRepo.save(wr);
    }

    @Transactional
    public ReroutePlan plan(Long planId) {
        return mustPlan(planId);
    }

    // ============================== 辅助 ==============================

    private WorkOrder mustOrder(Long id) {
        return orderRepo.findById(id).orElseThrow(() -> bad("作业单不存在"));
    }

    private ReroutePlan mustPlan(Long id) {
        return planRepo.findById(id).orElseThrow(() -> bad("重排计划不存在"));
    }

    private RerouteFarmerNotification mustNotification(Long id) {
        return notificationRepo.findById(id).orElseThrow(() -> bad("通知不存在"));
    }

    private void ensureCoop(WorkOrder o) {
        UserAccount u = currentUser.get();
        if (u.getRole() != E.Role.COOP || u.getCoop() == null
                || o.getCoop() == null || !o.getCoop().getId().equals(u.getCoop().getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅订单所属合作社可操作重排");
        }
    }

    private static double nz(Double v) { return v == null ? 0 : v; }
    private static double nz(Double v, double dft) { return v == null ? dft : v; }
    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }
}
