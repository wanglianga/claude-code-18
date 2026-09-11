package cn.county.agrimach.web;

import cn.county.agrimach.service.DocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 村干部侧：现场确认（地块边界丈量/面积争议等纳入档案），可参与纠纷调解 */
@RestController
@RequestMapping("/api/village")
@RequiredArgsConstructor
public class VillageController {

    private final DocumentService documentService;
    private final cn.county.agrimach.service.ArchiveService archiveService;

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
}
