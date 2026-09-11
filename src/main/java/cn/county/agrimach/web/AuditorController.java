package cn.county.agrimach.web;

import cn.county.agrimach.domain.entity.SubsidyClaim;
import cn.county.agrimach.domain.repo.Repos;
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
    private final Repos.ClaimRepo claimRepo;

    /** 待审核申报队列 */
    @GetMapping("/claims")
    public List<SubsidyClaim> pending() {
        return subsidyService.listForReview();
    }

    /** 申报单详情：含五分段明细与面积复核同步附件（复核证据） */
    @GetMapping("/claims/{id}")
    public SubsidyClaim detail(@PathVariable Long id) {
        SubsidyClaim c = claimRepo.findById(id).orElseThrow();
        // 显式初始化懒加载集合，保证明细与复核附件完整输出
        org.hibernate.Hibernate.initialize(c.getItems());
        org.hibernate.Hibernate.initialize(c.getAttachments());
        return c;
    }

    /** 审核通过/驳回（意见入档） */
    @PostMapping("/claims/{id}/review")
    public SubsidyClaim review(@PathVariable Long id, @RequestBody SubsidyService.ReviewReq req) {
        return subsidyService.review(id, req);
    }
}
