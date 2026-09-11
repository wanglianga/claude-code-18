package cn.county.agrimach.web;

import cn.county.agrimach.domain.entity.WorkException;
import cn.county.agrimach.domain.entity.WorkOrder;
import cn.county.agrimach.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 驾驶员侧：接单确认（机具状态/出发油量）、到地、作业轨迹、异常上报、完工 */
@RestController
@RequestMapping("/api/driver")
@RequiredArgsConstructor
public class DriverController {

    private final OrderService orderService;

    @GetMapping("/orders")
    public List<WorkOrder> myOrders() {
        return orderService.listDriverOrders();
    }

    /** 接单：确认机具状态 + 出发油量 */
    @PostMapping("/orders/{id}/accept")
    public WorkOrder accept(@PathVariable Long id, @RequestBody OrderService.AcceptReq req) {
        return orderService.accept(id, req);
    }

    /** 到地打卡 */
    @PostMapping("/orders/{id}/arrive")
    public WorkOrder arrive(@PathVariable Long id) {
        return orderService.arrive(id);
    }

    /** 开始作业 */
    @PostMapping("/orders/{id}/start")
    public WorkOrder start(@PathVariable Long id) {
        return orderService.startWork(id);
    }

    /** 上报实际作业轨迹点 */
    @PostMapping("/orders/{id}/track")
    public Map<String, Object> track(@PathVariable Long id, @RequestBody List<OrderService.TrackPointReq> points) {
        long n = orderService.uploadTrack(id, points);
        return Map.of("uploaded", n);
    }

    /** 现场异常上报（五类，触发作业中断，等待合作社重排重算） */
    @PostMapping("/orders/{id}/exceptions")
    public WorkException report(@PathVariable Long id, @RequestBody OrderService.ExceptionReq req) {
        return orderService.reportException(id, req);
    }

    /** 完工：返航油量 → 实际油耗；终算时间/费用/油补 */
    @PostMapping("/orders/{id}/finish")
    public WorkOrder finish(@PathVariable Long id, @RequestBody(required = false) OrderService.FinishReq req) {
        return orderService.finishWork(id, req != null ? req : new OrderService.FinishReq(null));
    }
}
