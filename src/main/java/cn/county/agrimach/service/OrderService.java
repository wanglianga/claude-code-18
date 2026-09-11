package cn.county.agrimach.service;

import cn.county.agrimach.domain.entity.*;
import cn.county.agrimach.domain.enums.E;
import cn.county.agrimach.domain.repo.Repos;
import cn.county.agrimach.service.support.CalcEngine;
import cn.county.agrimach.service.support.CalcPlan;
import cn.county.agrimach.service.support.Geo;
import cn.county.agrimach.service.support.PlanBuilder;
import cn.county.agrimach.service.support.PricingCatalog;
import cn.county.agrimach.service.support.TrackArea;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final Repos.OrderRepo orderRepo;
    private final Repos.SuggestionRepo suggestionRepo;
    private final Repos.MachineRepo machineRepo;
    private final Repos.DriverRepo driverRepo;
    private final Repos.CoopRepo coopRepo;
    private final Repos.WeatherRepo weatherRepo;
    private final Repos.SegmentRepo segmentRepo;
    private final Repos.ExceptionRepo exceptionRepo;
    private final Repos.RecalcRepo recalcRepo;
    private final Repos.SignoffRepo signoffRepo;
    private final Repos.TrackRepo trackRepo;
    private final Repos.MaintenanceRepo maintenanceRepo;
    private final CalcEngine calcEngine;
    private final PlanBuilder planBuilder;
    private final CurrentUser currentUser;

    private static final List<E.OrderStatus> ACTIVE = List.of(
            E.OrderStatus.DISPATCHED, E.OrderStatus.ACCEPTED, E.OrderStatus.EN_ROUTE,
            E.OrderStatus.ARRIVED, E.OrderStatus.IN_PROGRESS, E.OrderStatus.INTERRUPTED);

    // ============================== 农户预约 ==============================

    public record SubmitReq(String cropType, String plotName, String village,
                            Double longitude, Double latitude, Double bookedAreaMu,
                            E.OperationType operationType, E.MudLevel mudLevel,
                            @com.fasterxml.jackson.annotation.JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
                            LocalDateTime expectedStart,
                            @com.fasterxml.jackson.annotation.JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
                            LocalDateTime expectedEnd,
                            boolean strawRequested, String remark) {}

    @Transactional
    public WorkOrder submit(SubmitReq r) {
        UserAccount farmer = currentUser.get();
        if (r.longitude() == null || r.latitude() == null || r.bookedAreaMu() == null
                || r.operationType() == null || r.mudLevel() == null
                || r.expectedStart() == null || r.expectedEnd() == null) {
            throw bad("地块位置/面积/作业类型/泥泞程度/期望时间均为必填");
        }
        WorkOrder o = new WorkOrder();
        o.setFarmer(farmer);
        o.setCropType(r.cropType());
        o.setPlotName(r.plotName());
        o.setVillage(r.village() != null ? r.village() : farmer.getVillage());
        o.setLongitude(r.longitude());
        o.setLatitude(r.latitude());
        o.setBookedAreaMu(r.bookedAreaMu());
        o.setOperationType(r.operationType());
        o.setMudLevel(r.mudLevel());
        o.setExpectedStart(r.expectedStart());
        o.setExpectedEnd(r.expectedEnd());
        o.setStrawRequested(r.strawRequested());
        o.setRemark(r.remark());
        o.setStatus(E.OrderStatus.SUBMITTED);
        return orderRepo.save(o);
    }

    // ============================== 派机建议 ==============================

    @Transactional
    public List<DispatchSuggestion> generateSuggestions(Long orderId) {
        WorkOrder o = mustOrder(orderId);
        // 只有未分派订单在共享调度池中开放给各社竞价派机；已派给他社的订单不可再触达
        if (o.getStatus() != E.OrderStatus.SUBMITTED) {
            throw bad("订单当前状态为「" + o.getStatus().label + "」，不能再生成派机建议");
        }
        suggestionRepo.deleteByWorkOrderId(orderId);
        suggestionRepo.flush();

        List<DispatchSuggestion> out = new ArrayList<>();
        for (Machine m : machineRepo.findByEnabledTrue()) {
            if (!m.supports(o.getOperationType())) continue;
            // 需要秸秆处理时，机具须同时具备秸秆处理能力（如带打捆装置的收割机）
            if (o.isStrawRequested() && !m.supports(E.OperationType.STRAW_TREATMENT)) continue;

            Cooperative coop = m.getCoop();
            Driver driver = pickDriver(m, coop, o);
            if (driver == null) continue;

            double roadKm = Geo.roadKm(coop.getLongitude(), coop.getLatitude(), o.getLongitude(), o.getLatitude());
            E.WeatherType weather = weatherAt(o.getVillage(), o.getExpectedStart().toLocalDate());
            int workMin = planBuilder.initialWorkMinutes(o, m);
            int haulMin = planBuilder.minutesForHaul(roadKm, m);

            LocalDateTime earliestDeparture = o.getExpectedStart();
            String weatherNote = "";
            double weatherPenalty = 0;
            if (weather.isRain()) {
                // 雨日无法进地 → 顺延到下一个非雨日（查天气预报）
                LocalDate d = o.getExpectedStart().toLocalDate();
                int shift = 0;
                while (shift < 5 && weatherAt(o.getVillage(), d).isRain()) { d = d.plusDays(1); shift++; }
                earliestDeparture = d.atTime(o.getExpectedStart().toLocalTime());
                weatherPenalty = 8 + shift * 4;
                weatherNote = "派机窗口遇" + weather.label + "，顺延" + shift + "天;";
            }

            // 初算（无异常）费用/油补（按候选合作社距离）
            var initial = planBuilder.build(o, m, List.of(), roadKm);
            CalcPlan plan = calcEngine.calculate(o.getOperationType(), o.getMudLevel(), m,
                    initial.effectiveAreaMu(), initial.segments());

            double fatigueLoad = driver.getTodayWorkMinutes()
                    + nz(orderRepo.sumDriverMinutesOnDay(driver.getId(), ACTIVE,
                            earliestDeparture.toLocalDate().atStartOfDay(),
                            earliestDeparture.toLocalDate().plusDays(1).atStartOfDay()));
            double fatiguePenalty = Math.min(40, fatigueLoad / Math.max(1, driver.getFatigueLimitMinutes()) * 40);
            double delayHours = Math.max(0, java.time.Duration.between(o.getExpectedStart(), earliestDeparture).toMinutes() / 60.0);
            double score = Math.round((roadKm * 1.5 + fatiguePenalty + weatherPenalty + delayHours * 6) * 10) / 10.0;

            DispatchSuggestion s = new DispatchSuggestion(o, coop, m, driver);
            s.setRoadDistanceKm(round2(roadKm));
            s.setEarliestDeparture(earliestDeparture);
            s.setEstimatedArrival(earliestDeparture.plusMinutes(haulMin));
            s.setEstimatedWorkMinutes(workMin);
            s.setWeather(weather);
            s.setScore(score);
            s.setEstimatedFee(plan.farmerFee);
            s.setEstimatedSubsidy(plan.subsidyTotal);
            s.setReasonText(String.format(
                    "道路距离%.1fkm+%.0f分; %s驾驶员当日负荷%.0f分钟疲劳+%.0f分; 预计费用%.2f元油补%.2f元; 预计作业%d分钟",
                    roadKm, roadKm * 1.5, weatherNote, fatigueLoad, fatiguePenalty,
                    plan.farmerFee, plan.subsidyTotal, workMin));
            out.add(suggestionRepo.save(s));
        }
        out.sort(Comparator.comparingDouble(DispatchSuggestion::getScore));
        if (out.isEmpty()) throw bad("暂无符合 “" + o.getOperationType().label
                + (o.isStrawRequested() ? "+秸秆处理" : "") + "” 机具类型与驾驶员资质的候选资源");
        return out;
    }

    private Driver pickDriver(Machine m, Cooperative coop, WorkOrder o) {
        return driverRepo.findByCoopId(coop.getId()).stream()
                .filter(d -> d.qualifiedFor(m.getType().name()))
                .min(Comparator.comparingInt(Driver::getTodayWorkMinutes))
                .orElse(null);
    }

    private E.WeatherType weatherAt(String village, LocalDate date) {
        return weatherRepo.findByVillageAndDate(village, date.toString())
                .map(WeatherRecord::getWeather).orElse(E.WeatherType.SUNNY);
    }

    // ============================== 合作社派机 ==============================

    @Transactional
    public WorkOrder dispatch(Long orderId, Long suggestionId, String dispatchOpinion) {
        WorkOrder o = mustOrder(orderId);
        if (o.getStatus() != E.OrderStatus.SUBMITTED) throw bad("当前状态不允许派机: " + o.getStatus().label);
        DispatchSuggestion s = suggestionRepo.findById(suggestionId)
                .filter(x -> x.getWorkOrder().getId().equals(orderId))
                .orElseThrow(() -> bad("派机建议不存在"));
        suggestionRepo.findByWorkOrderId(orderId).forEach(x -> x.setSelected(x.getId().equals(suggestionId)));

        o.setCoop(s.getCoop());
        o.setMachine(s.getMachine());
        o.setDriver(s.getDriver());
        o.setRoadDistanceKm(s.getRoadDistanceKm());
        o.setDispatchWeather(s.getWeather());
        o.setEstimatedEmptyHaulMinutes(planBuilder.minutesForHaul(s.getRoadDistanceKm(), s.getMachine()));
        o.setStatus(E.OrderStatus.DISPATCHED);
        applyCalc(o, null, "首次派机核算（版本1）", "COOP", true);

        // 三方签批占位：合作社调度意见即时落档；村干部确认、农户签字待流程推进
        Signoff dispatchSign = new Signoff(o, E.SignoffType.COOP_DISPATCH);
        dispatchSign.setStatus(E.SignoffStatus.APPROVED);
        dispatchSign.setSigner(currentUser.get());
        dispatchSign.setOpinion(dispatchOpinion != null ? dispatchOpinion : "按最优派机建议调度");
        dispatchSign.setSignedAt(LocalDateTime.now());
        signoffRepo.save(dispatchSign);
        signoffRepo.save(new Signoff(o, E.SignoffType.VILLAGE_CONFIRM));
        signoffRepo.save(new Signoff(o, E.SignoffType.FARMER_SIGN));
        return o;
    }

    // ============================== 驾驶员接单/作业 ==============================

    public record AcceptReq(String machineCondition, Double departureFuelL) {}

    @Transactional
    public WorkOrder accept(Long orderId, AcceptReq r) {
        WorkOrder o = driverOrder(orderId);
        if (o.getStatus() != E.OrderStatus.DISPATCHED) throw bad("当前状态不可接单: " + o.getStatus().label);
        o.setAcceptedAt(LocalDateTime.now());
        o.setMachineConditionConfirmed(r.machineCondition());
        o.setDepartureFuelL(r.departureFuelL());
        o.setDepartedAt(LocalDateTime.now());
        o.setStatus(E.OrderStatus.EN_ROUTE);
        return o;
    }

    @Transactional
    public WorkOrder arrive(Long orderId) {
        WorkOrder o = driverOrder(orderId);
        if (o.getStatus() != E.OrderStatus.EN_ROUTE) throw bad("尚未出发: " + o.getStatus().label);
        o.setArrivedAt(LocalDateTime.now());
        o.setStatus(E.OrderStatus.ARRIVED);
        return o;
    }

    @Transactional
    public WorkOrder startWork(Long orderId) {
        WorkOrder o = driverOrder(orderId);
        if (o.getStatus() != E.OrderStatus.ARRIVED && o.getStatus() != E.OrderStatus.INTERRUPTED) {
            throw bad("到地后才能开始作业: " + o.getStatus().label);
        }
        if (o.getWorkStartedAt() == null) o.setWorkStartedAt(LocalDateTime.now());
        o.setStatus(E.OrderStatus.IN_PROGRESS);
        return o;
    }

    public record TrackPointReq(Integer seq,
                                @com.fasterxml.jackson.annotation.JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
                                LocalDateTime pointTime,
                                Double longitude, Double latitude, Double speedKmh,
                                boolean working, String note) {}

    @Transactional
    public long uploadTrack(Long orderId, List<TrackPointReq> points) {
        WorkOrder o = driverOrder(orderId);
        long base = trackRepo.countByWorkOrderId(orderId);
        int i = 0;
        for (TrackPointReq p : points) {
            TrackPoint tp = new TrackPoint();
            tp.setWorkOrder(o);
            tp.setSeq(p.seq() != null ? p.seq() : (int) base + i + 1);
            tp.setPointTime(p.pointTime() != null ? p.pointTime() : LocalDateTime.now());
            tp.setLongitude(p.longitude());
            tp.setLatitude(p.latitude());
            tp.setSpeedKmh(p.speedKmh());
            tp.setWorking(p.working());
            tp.setNote(p.note());
            trackRepo.save(tp);
            i++;
        }
        return i;
    }

    // ============================== 现场异常与重算 ==============================

    public record ExceptionReq(E.ExceptionType type, String description, String evidence,
                               Double measuredAreaMu, Integer downtimeMinutes, Double reworkAreaMu,
                               E.OperationType addedOperation, Double addedAreaMu, Double extraEmptyHaulKm) {}

    @Transactional
    public WorkException reportException(Long orderId, ExceptionReq r) {
        WorkOrder o = mustOrder(orderId);
        UserAccount reporter = currentUser.get();
        // 驾驶员只能上报本人承接的作业；农户可上报自己的预约单
        if (reporter.getRole() == E.Role.DRIVER
                && (o.getDriver() == null || !o.getDriver().getUser().getId().equals(reporter.getId()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能上报本人承接的作业");
        }
        if (reporter.getRole() == E.Role.FARMER && !o.getFarmer().getId().equals(reporter.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能上报本人预约的作业");
        }
        if (o.getStatus() == E.OrderStatus.SETTLED || o.getStatus() == E.OrderStatus.INVOICED
                || o.getStatus() == E.OrderStatus.CANCELLED) {
            throw bad("作业已结算，费用调整须通过纠纷流程");
        }
        WorkException ex = new WorkException();
        ex.setWorkOrder(o);
        ex.setType(r.type());
        ex.setReportedBy(reporter);
        ex.setDescription(r.description());
        ex.setEvidence(r.evidence());
        ex.setMeasuredAreaMu(r.measuredAreaMu());
        ex.setDowntimeMinutes(r.downtimeMinutes() != null ? r.downtimeMinutes() : 0);
        ex.setReworkAreaMu(r.reworkAreaMu() != null ? r.reworkAreaMu() : 0.0);
        ex.setAddedOperation(r.addedOperation());
        ex.setAddedAreaMu(r.addedAreaMu() != null ? r.addedAreaMu() : 0.0);
        ex.setExtraEmptyHaulKm(r.extraEmptyHaulKm() != null ? r.extraEmptyHaulKm() : 0.0);
        ex.setCalcVersionAtReport(o.getCalcVersion());
        exceptionRepo.save(ex);
        o.setStatus(E.OrderStatus.INTERRUPTED);
        return ex;
    }

    public record ResolveReq(Double measuredAreaMu, Integer downtimeMinutes, Double reworkAreaMu,
                             E.OperationType addedOperation, Double addedAreaMu, Double extraEmptyHaulKm,
                             String resolution) {}

    @Transactional
    public WorkException resolveException(Long exceptionId, ResolveReq r) {
        WorkException ex = exceptionRepo.findById(exceptionId).orElseThrow(() -> bad("异常不存在"));
        WorkOrder o = ex.getWorkOrder();
        if (ex.getState() == E.ExceptionState.RESOLVED) throw bad("该异常已处置");
        if (r.measuredAreaMu() != null) ex.setMeasuredAreaMu(r.measuredAreaMu());
        if (r.downtimeMinutes() != null) ex.setDowntimeMinutes(r.downtimeMinutes());
        if (r.reworkAreaMu() != null) ex.setReworkAreaMu(r.reworkAreaMu());
        if (r.addedOperation() != null) ex.setAddedOperation(r.addedOperation());
        if (r.addedAreaMu() != null) ex.setAddedAreaMu(r.addedAreaMu());
        if (r.extraEmptyHaulKm() != null) ex.setExtraEmptyHaulKm(r.extraEmptyHaulKm());

        // 边界不清的实测面积必须有村干部现场确认，才能作为费用调整依据
        if (ex.getType() == E.ExceptionType.UNCLEAR_BOUNDARY) {
            Signoff vc = signoffRepo.findByWorkOrderIdAndType(o.getId(), E.SignoffType.VILLAGE_CONFIRM)
                    .orElseThrow(() -> bad("缺少村干部确认签批"));
            if (vc.getStatus() != E.SignoffStatus.APPROVED) {
                throw bad("地块边界不清的实测面积须先取得村干部确认，再处置异常");
            }
        }
        ex.setResolution(r.resolution());
        ex.setResolvedAt(LocalDateTime.now());
        ex.setState(E.ExceptionState.RESOLVED);

        applyCalc(o, ex, "异常[" + ex.getType().label + "]重排重算: " + r.resolution(), "COOP", false);
        // 机具故障 → 自动登记维修窗口（进入维修台账与合作社视图）
        if (ex.getType() == E.ExceptionType.MACHINE_FAULT && o.getMachine() != null) {
            MaintenanceRecord mr = new MaintenanceRecord();
            mr.setMachine(o.getMachine());
            mr.setWorkOrder(o);
            mr.setDescription("作业现场故障: " + ex.getDescription());
            mr.setWindowStart(LocalDateTime.now());
            mr.setWindowEnd(LocalDateTime.now().plusHours(4));
            mr.setStatus(E.RepairStatus.PLANNED);
            mr.setHourMeterAtRepair(o.getMachine().getHourMeter());
            maintenanceRepo.save(mr);
        }
        if (o.getStatus() == E.OrderStatus.INTERRUPTED) o.setStatus(E.OrderStatus.IN_PROGRESS);
        return ex;
    }

    // ============================== 完工/验收 ==============================

    public record FinishReq(Double returnFuelL) {}

    @Transactional
    public WorkOrder finishWork(Long orderId, FinishReq r) {
        WorkOrder o = driverOrder(orderId);
        if (o.getWorkStartedAt() == null) throw bad("尚未开始作业");
        // 仍有未处置异常时不允许完工
        long open = exceptionRepo.findByWorkOrderId(orderId).stream()
                .filter(e -> e.getState() == E.ExceptionState.OPEN).count();
        if (open > 0) throw bad("还有 " + open + " 起现场异常未处置，无法完工");

        o.setWorkFinishedAt(LocalDateTime.now());
        applyCalc(o, null, "完工终算（按实测面积/实际等待/返工/追加）", "DRIVER", false);

        // 轨迹核算面积作为验收证据
        double trackArea = TrackArea.polygonMu(trackRepo.findByWorkOrderIdOrderBySeq(orderId));
        o.setTrackAreaMu(trackArea);
        if (o.getActualAreaMu() == null) {
            // 终算的有效面积：取最后一次重算计划面积
            o.setActualAreaMu(lastEffectiveArea(o));
        }
        if (r.returnFuelL() != null && o.getDepartureFuelL() != null) {
            o.setActualFuelL(round2(Math.max(0, o.getDepartureFuelL() - r.returnFuelL())));
        }
        // 工时/小时台账：支撑疲劳度、利用率、维修窗口
        if (o.getDriver() != null) {
            int duty = nz(o.getEstimatedWorkMinutes()) + nz(o.getEstimatedEmptyHaulMinutes());
            o.getDriver().setTodayWorkMinutes(o.getDriver().getTodayWorkMinutes() + duty);
        }
        if (o.getMachine() != null && o.getEstimatedWorkMinutes() != null) {
            o.getMachine().setHourMeter(round2(o.getMachine().getHourMeter() + o.getEstimatedWorkMinutes() / 60.0));
        }
        o.setStatus(E.OrderStatus.COMPLETED);
        return o;
    }

    public record AcceptWorkReq(Double actualAreaMu, Integer rating, String comment) {}

    @Transactional
    public WorkOrder farmerAccept(Long orderId, AcceptWorkReq r) {
        WorkOrder o = mustOrder(orderId);
        UserAccount u = currentUser.get();
        if (!o.getFarmer().getId().equals(u.getId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅预约农户可验收");
        if (o.getStatus() != E.OrderStatus.COMPLETED) throw bad("作业未完成，无法验收: " + o.getStatus().label);
        if (r.actualAreaMu() != null) o.setActualAreaMu(r.actualAreaMu());
        o.setFarmerRating(r.rating());
        o.setFarmerAcceptComment(r.comment());
        o.setFarmerAcceptedAt(LocalDateTime.now());
        o.setStatus(E.OrderStatus.SETTLED);
        Signoff sign = signoffRepo.findByWorkOrderIdAndType(orderId, E.SignoffType.FARMER_SIGN)
                .orElseGet(() -> signoffRepo.save(new Signoff(o, E.SignoffType.FARMER_SIGN)));
        sign.setStatus(E.SignoffStatus.APPROVED);
        sign.setSigner(u);
        sign.setOpinion("验收" + (r.comment() != null ? ": " + r.comment() : "确认"));
        sign.setSignedAt(LocalDateTime.now());
        return o;
    }

    // ============================== 核算（版本化留痕） ==============================

    @Transactional
    public Recalculation applyCalc(WorkOrder o, WorkException trigger, String reason, String role, boolean firstVersion) {
        List<WorkException> resolved = exceptionRepo.findByWorkOrderId(o.getId()).stream()
                .filter(e -> e.getState() == E.ExceptionState.RESOLVED).toList();
        PlanBuilder.BuiltPlan bp = planBuilder.build(o, o.getMachine(), resolved);
        CalcPlan plan = calcEngine.calculate(o.getOperationType(), o.getMudLevel(), o.getMachine(),
                bp.effectiveAreaMu(), bp.segments());

        double oldArea = o.getActualAreaMu() != null ? o.getActualAreaMu() : o.getBookedAreaMu();
        int oldMin = nz(o.getEstimatedWorkMinutes());
        double oldFee = nz(o.getFarmerFee());
        double oldSub = nz(o.getSubsidyTotal());

        int version = firstVersion ? 1 : o.getCalcVersion() + 1;
        int newWorkMinutes = plan.workMinutes + plan.reworkMinutes;

        // 终算结果与现行版本一致（无新增异常影响）时不升版本、不重复写分段
        boolean unchanged = !firstVersion
                && eq(bp.effectiveAreaMu(), o.getActualAreaMu())
                && newWorkMinutes == nz(o.getEstimatedWorkMinutes())
                && eq(plan.farmerFee, o.getFarmerFee())
                && eq(plan.subsidyTotal, o.getSubsidyTotal());
        if (unchanged) {
            return null;
        }

        // 以新版本重写分段（旧版本保留，供逐版审计）
        for (CalcPlan.SegResult sr : plan.segments) {
            WorkSegment ws = new WorkSegment();
            ws.setWorkOrder(o);
            ws.setSegmentType(sr.type());
            ws.setOperationType(sr.op());
            LocalDateTime base = o.getExpectedStart();
            ws.setStartTime(base);
            ws.setEndTime(base.plusMinutes(sr.minutes()));
            ws.setDurationMinutes(sr.minutes());
            ws.setAreaMu(sr.areaMu());
            ws.setDistanceKm(sr.distanceKm());
            ws.setFuelL(sr.fuelL());
            ws.setRuleRate(sr.rate());
            ws.setSubsidyAmount(sr.subsidy());
            ws.setCalcVersion(version);
            // 仅分段自身来源的异常（额外空驶/等待/故障/返工/追加）关联；基础空驶不挂异常
            ws.setExceptionId(sr.exceptionId());
            ws.setNote(sr.note());
            segmentRepo.save(ws);
        }

        o.setCalcVersion(version);
        o.setUnitPrice(PricingCatalog.of(o.getOperationType()).unitPrice());
        o.setExtraUnitPrice(PricingCatalog.of(o.getOperationType()).extraUnitPrice());
        o.setActualAreaMu(bp.effectiveAreaMu());
        o.setEstimatedWorkMinutes(newWorkMinutes);
        o.setEstimatedEmptyHaulMinutes(plan.emptyHaulMinutes);
        o.setFarmerFee(plan.farmerFee);
        o.setEmptyHaulFee(plan.emptyHaulFee);
        o.setWaitingFee(plan.waitingFee);
        o.setReworkCost(plan.reworkCost);
        o.setSubsidyTotal(plan.subsidyTotal);

        Recalculation rec = new Recalculation();
        rec.setWorkOrder(o);
        rec.setWorkException(trigger);
        rec.setVersion(version);
        rec.setReason(reason);
        rec.setTriggerRole(role);
        rec.setOldAreaMu(round2(oldArea));
        rec.setNewAreaMu(bp.effectiveAreaMu());
        rec.setOldWorkMinutes(oldMin);
        rec.setNewWorkMinutes(newWorkMinutes);
        rec.setOldFarmerFee(round2(oldFee));
        rec.setNewFarmerFee(plan.farmerFee);
        rec.setOldSubsidy(round2(oldSub));
        rec.setNewSubsidy(plan.subsidyTotal);
        rec.setSegmentDeltaText(plan.segments.stream()
                .map(s -> s.type().label + "(" + s.op().label + "):" + s.minutes() + "分/"
                        + s.areaMu() + "亩/" + s.distanceKm() + "km→补" + s.subsidy() + "元")
                .reduce((a, b) -> a + "; " + b).orElse(""));
        return recalcRepo.save(rec);
    }

    private Double lastEffectiveArea(WorkOrder o) {
        return recalcRepo.findByWorkOrderIdOrderByVersion(o.getId()).stream()
                .reduce((a, b) -> b).map(Recalculation::getNewAreaMu).orElse(o.getBookedAreaMu());
    }

    // ============================== 查询辅助 ==============================

    @Transactional
    public List<WorkOrder> listFarmerOrders() {
        return orderRepo.findByFarmerIdOrderByCreatedAtDesc(currentUser.get().getId());
    }

    @Transactional
    public List<WorkOrder> listDriverOrders() {
        return driverRepo.findByUserId(currentUser.get().getId())
                .map(d -> orderRepo.findByDriverId(d.getId()))
                .orElse(List.of());
    }

    /**
     * 调度池（合作社视图），按合作社归属过滤：
     * - 未分派（SUBMITTED）预约单：全县共享调度池，所有合作社均可查看并派机；
     * - 已派机（DISPATCHED）订单：仅订单归属合作社可见（本社在途跟踪），
     *   其他合作社看不到他社订单。
     */
    @Transactional
    public List<WorkOrder> listPendingPool() {
        UserAccount u = currentUser.get();
        if (u.getRole() != E.Role.COOP || u.getCoop() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅合作社账号可查看调度池");
        }
        Long coopId = u.getCoop().getId();
        List<WorkOrder> pool = new ArrayList<>(
                orderRepo.findByStatusIn(List.of(E.OrderStatus.SUBMITTED)));
        pool.addAll(orderRepo.findByCoopIdAndStatus(coopId, E.OrderStatus.DISPATCHED));
        pool.sort(Comparator.comparing(WorkOrder::getCreatedAt,
                Comparator.nullsLast(Comparator.naturalOrder())).reversed());
        return pool;
    }

    public WorkOrder driverOrder(Long orderId) {
        WorkOrder o = mustOrder(orderId);
        UserAccount u = currentUser.get();
        if (u.getRole() == E.Role.DRIVER) {
            if (o.getDriver() == null || !o.getDriver().getUser().getId().equals(u.getId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "该作业单不属于当前驾驶员");
            }
        }
        return o;
    }

    public WorkOrder mustOrder(Long id) {
        return orderRepo.findById(id).orElseThrow(() -> bad("作业单不存在: " + id));
    }

    public Long currentUserId() {
        return currentUser.get().getId();
    }

    private static int nz(Integer v) { return v == null ? 0 : v; }
    private static double nz(Double v) { return v == null ? 0 : v; }
    private static boolean eq(Double a, Double b) {
        return a != null && b != null && Math.abs(a - b) < 0.005;
    }
    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }

    /** 时间格式（控制器展示复用） */
    public static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
}
