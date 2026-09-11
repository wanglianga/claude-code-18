package cn.county.agrimach.web;

import cn.county.agrimach.domain.entity.SubsidyClaim;
import cn.county.agrimach.service.SubsidyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 补贴审核部门侧：审查申报材料，每条明细可下钻统一作业档案解释重排原因 */
@RestController
@RequestMapping("/api/auditor")
@RequiredArgsConstructor
public class AuditorController {

    private final SubsidyService subsidyService;

    /** 待审核申报队列 */
    @GetMapping("/claims")
    public List<SubsidyClaim> pending() {
        return subsidyService.listForReview();
    }

    /** 审核通过/驳回（意见入档） */
    @PostMapping("/claims/{id}/review")
    public SubsidyClaim review(@PathVariable Long id, @RequestBody SubsidyService.ReviewReq req) {
        return subsidyService.review(id, req);
    }
}
