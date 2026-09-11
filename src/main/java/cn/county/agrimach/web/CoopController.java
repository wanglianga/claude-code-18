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

    private Long currentCoopId() {
        var coop = currentUser.get().getCoop();
        if (coop == null) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.FORBIDDEN, "当前账号未绑定合作社");
        return coop.getId();
    }
}
