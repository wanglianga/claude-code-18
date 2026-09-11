package cn.county.agrimach.web;

import cn.county.agrimach.domain.entity.WorkOrder;
import cn.county.agrimach.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 农户侧：提交预约、查看本人作业单、农户验收签字 */
@RestController
@RequestMapping("/api/farmer")
@RequiredArgsConstructor
public class FarmerController {

    private final OrderService orderService;
    private final cn.county.agrimach.service.AreaReviewService areaReviewService;

    @PostMapping("/orders")
    public WorkOrder submit(@RequestBody OrderService.SubmitReq req) {
        return orderService.submit(req);
    }

    @GetMapping("/orders")
    public List<WorkOrder> myOrders() {
        return orderService.listFarmerOrders();
    }

    @GetMapping("/orders/{id}")
    public WorkOrder get(@PathVariable Long id) {
        WorkOrder o = orderService.mustOrder(id);
        if (!o.getFarmer().getId().equals(orderService.currentUserId())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "只能查看本人预约单");
        }
        return o;
    }

    /** 作业结束后农户验收（确认实际面积、评分、签字） */
    @PostMapping("/orders/{id}/accept-work")
    public WorkOrder acceptWork(@PathVariable Long id, @RequestBody OrderService.AcceptWorkReq req) {
        return orderService.farmerAccept(id, req);
    }

    /** 农户上报现场异常（如临时要求增加作业），触发合作社重排重算 */
    @PostMapping("/orders/{id}/exceptions")
    public cn.county.agrimach.domain.entity.WorkException reportException(
            @PathVariable Long id, @RequestBody OrderService.ExceptionReq req) {
        return orderService.reportException(id, req);
    }

    /** 发起地块面积争议复核（收费面积过高时），归集轨迹/卫星边界/验收照片等证据 */
    @PostMapping("/orders/{id}/area-reviews")
    public cn.county.agrimach.domain.entity.AreaReview raiseReview(
            @PathVariable Long id,
            @RequestBody cn.county.agrimach.service.AreaReviewService.RaiseReq req) {
        return areaReviewService.raise(id, req);
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        return orderService.farmerProfile();
    }

    /** 查看本单面积争议复核进展 */
    @GetMapping("/orders/{id}/area-reviews")
    public Object listReviews(@PathVariable Long id) {
        return areaReviewService.listForOrder(id);
    }
}
