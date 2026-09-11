package cn.county.agrimach.web;

import cn.county.agrimach.domain.entity.AreaReview;
import cn.county.agrimach.domain.entity.UserAccount;
import cn.county.agrimach.service.AreaReviewService;
import cn.county.agrimach.service.DocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 村干部侧：现场确认、边界预确认、面积争议复核裁决、纠纷调解 */
@RestController
@RequestMapping("/api/village")
@RequiredArgsConstructor
public class VillageController {

    private final DocumentService documentService;
    private final cn.county.agrimach.service.ArchiveService archiveService;
    private final AreaReviewService areaReviewService;

    /** 村干部确认：边界不清/面积争议的实测结果必须经此确认后方可重算 */
    @PostMapping("/orders/{id}/confirm")
    public Object confirm(@PathVariable Long id, @RequestBody DocumentService.VillageConfirmReq req) {
        return documentService.villageConfirm(id, req);
    }

    /** 村干部调解纠纷 */
    @PostMapping("/disputes/{disputeId}/resolve")
    public Object resolveDispute(@PathVariable Long disputeId,
                                 @RequestBody cn.county.agrimach.service.ArchiveService.ResolveDisputeReq req) {
        return archiveService.resolveDispute(disputeId, req);
    }

    /** 为诚信风险农户的下一宗地块做边界预确认（后续预约的前置条件） */
    @PostMapping("/farmers/{farmerId}/boundary-pre-confirm")
    public UserAccount preConfirmBoundary(@PathVariable Long farmerId,
                                          @RequestBody AreaReviewService.BoundaryPreConfirmReq req) {
        return areaReviewService.preConfirmBoundary(farmerId, req);
    }

    /** 录入卫星地块边界证据（自动核算卫星面积） */
    @PostMapping("/area-reviews/{reviewId}/satellite")
    public AreaReview satellite(@PathVariable Long reviewId,
                                @RequestBody AreaReviewService.SatelliteReq req) {
        return areaReviewService.satelliteBoundary(reviewId, req);
    }

    /** 面积争议复核裁决：超算退减 / 少报记诚信风险，费用与油补同步调整 */
    @PostMapping("/area-reviews/{reviewId}/decide")
    public AreaReview decide(@PathVariable Long reviewId, @RequestBody AreaReviewService.DecideReq req) {
        return areaReviewService.decide(reviewId, req);
    }

    @GetMapping("/orders/{orderId}/area-reviews")
    public Object listReviews(@PathVariable Long orderId) {
        return areaReviewService.listForOrder(orderId);
    }
}
