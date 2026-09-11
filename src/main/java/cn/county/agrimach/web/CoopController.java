package cn.county.agrimach.web;

import cn.county.agrimach.domain.entity.DispatchSuggestion;
import cn.county.agrimach.domain.entity.WorkException;
import cn.county.agrimach.domain.entity.WorkOrder;
import cn.county.agrimach.service.CoopService;
import cn.county.agrimach.service.DocumentService;
import cn.county.agrimach.service.OrderService;
import cn.county.agrimach.service.SubsidyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 合作社侧：看到的是跨村路线/利用率/疲劳/维修窗口，而非单个订单；
 * 同时负责派机建议、异常处置重算、调度意见、开票与补贴申报。
 */
@RestController
@RequestMapping("/api/coop")
@RequiredArgsConstructor
public class CoopController {

    private final OrderService orderService;
    private final CoopService coopService;
    private final DocumentService documentService;
    private final SubsidyService subsidyService;
    private final cn.county.agrimach.service.CurrentUser currentUser;
    private final cn.county.agrimach.service.AreaReviewService areaReviewService;
    private final cn.county.agrimach.service.RerouteService rerouteService;

    /** 待派机池 */
    @GetMapping("/dispatch/pool")
    public List<WorkOrder> pool() {
        return orderService.listPendingPool();
    }

    /** 生成派机建议（按机具类型/排班/资质/距离/天气/油补规则评分） */
    @PostMapping("/orders/{id}/suggestions")
    public List<DispatchSuggestion> suggest(@PathVariable Long id) {
        return orderService.generateSuggestions(id);
    }

    public record DispatchReq(Long suggestionId, String opinion) {}

    /** 采纳建议派机（首版费用/油补核算落档） */
    @PostMapping("/orders/{id}/dispatch")
    public WorkOrder dispatch(@PathVariable Long id, @RequestBody DispatchReq req) {
        return orderService.dispatch(id, req.suggestionId(), req.opinion());
    }

    /** 处置现场异常 → 重算作业时间/费用/油补并留痕 */
    @PostMapping("/exceptions/{exceptionId}/resolve")
    public WorkException resolve(@PathVariable Long exceptionId, @RequestBody OrderService.ResolveReq req) {
        return orderService.resolveException(exceptionId, req);
    }

    /** 追加合作社调度意见（解释每次重排原因） */
    @PostMapping("/orders/{id}/opinion")
    public Object opinion(@PathVariable Long id, @RequestBody DocumentService.CoopOpinionReq req) {
        return documentService.coopOpinion(id, req);
    }

    /** 农户验收后开票 */
    @PostMapping("/orders/{id}/invoice")
    public Object invoice(@PathVariable Long id) {
        return documentService.issueInvoice(id);
    }

    /** 跨村路线（默认今天，日期 yyyy-MM-dd） */
    @GetMapping("/routes")
    public CoopService.CrossVillageRoute routes(@RequestParam(required = false) String date) {
        java.time.LocalDate d = date != null ? java.time.LocalDate.parse(date) : java.time.LocalDate.now();
        return coopService.crossVillageRoutes(currentCoopId(), d);
    }

    /** 机具利用率 */
    @GetMapping("/utilization")
    public List<CoopService.MachineUtilization> utilization() {
        return coopService.utilization(currentCoopId());
    }

    /** 驾驶员疲劳监测 */
    @GetMapping("/fatigue")
    public List<CoopService.DriverFatigue> fatigue() {
        return coopService.fatigue(currentCoopId());
    }

    /** 维修窗口 */
    @GetMapping("/maintenance")
    public List<CoopService.MaintenanceWindow> maintenance() {
        return coopService.maintenanceWindows(currentCoopId());
    }

    /** 补贴申报：生成草稿（跨村多单分段汇总） */
    @PostMapping("/subsidy/draft")
    public Object draftClaim(@RequestParam String period) {
        return subsidyService.createDraft(currentCoopId(), period);
    }

    @PostMapping("/subsidy/{claimId}/submit")
    public Object submitClaim(@PathVariable Long claimId) {
        return subsidyService.submit(claimId);
    }

    /** 合作社调取/录入卫星地块边界证据，供面积争议复核 */
    @PostMapping("/area-reviews/{reviewId}/satellite")
    public Object reviewSatellite(@PathVariable Long reviewId,
                                  @RequestBody cn.county.agrimach.service.AreaReviewService.SatelliteReq req) {
        return areaReviewService.satelliteBoundary(reviewId, req);
    }

    @GetMapping("/orders/{orderId}/area-reviews")
    public Object listReviews(@PathVariable Long orderId) {
        return areaReviewService.listForOrder(orderId);
    }

    // ---------- 雨后作业窗口重排 ----------

    /** 录入/更新某村某日天气与土壤墒情（决定机具能否进地） */
    @PostMapping("/reroute/weather")
    public Object upsertWeather(@RequestBody cn.county.agrimach.service.RerouteService.WeatherUpsertReq req) {
        return rerouteService.upsertWeather(req);
    }

    /** 扫描本社当日因雨后土壤湿度无法进机的作业单 */
    @GetMapping("/reroute/blocked")
    public Object blocked(@RequestParam(required = false) String date) {
        java.time.LocalDate d = date != null ? java.time.LocalDate.parse(date) : java.time.LocalDate.now();
        return rerouteService.scanBlocked(currentCoopId(), d);
    }

    /** 生成重排计划（按湿度/成熟紧迫度/农机位置/其他村预约排序） */
    @PostMapping("/orders/{id}/reroute/plan")
    public Object buildReroute(@PathVariable Long id) {
        return rerouteService.buildPlan(id);
    }

    /** 合作社决策：DIVERT 先转去可作业地块 / WAIT 原地等待 */
    @PostMapping("/reroute/{planId}/decide")
    public Object decideReroute(@PathVariable Long planId,
                                @RequestBody cn.county.agrimach.service.RerouteService.DecideReq req) {
        return rerouteService.decide(planId, req);
    }

    @GetMapping("/reroute/plans")
    public Object reroutePlans() {
        return rerouteService.listPlans(currentCoopId());
    }

    /** 处理农户“要求换机具”：自动选择目标日可进地的最近同类机具 */
    @PostMapping("/reroute/notifications/{notificationId}/change-machine")
    public Object changeMachine(@PathVariable Long notificationId) {
        return rerouteService.handleMachineChange(notificationId);
    }

    /** 确认农户取消作业 */
    @PostMapping("/reroute/notifications/{notificationId}/cancel")
    public Object cancelByFarmer(@PathVariable Long notificationId) {
        return rerouteService.handleCancel(notificationId);
    }

    /** 合作社调度评分（重排通知响应+换机+取消+有效作业比例） */
    @GetMapping("/reroute/score")
    public Object rerouteScore() {
        return rerouteService.dispatchScore(currentCoopId());
    }

    private Long currentCoopId() {
        var coop = currentUser.get().getCoop();
        if (coop == null) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.FORBIDDEN, "当前账号未绑定合作社");
        return coop.getId();
    }
}
