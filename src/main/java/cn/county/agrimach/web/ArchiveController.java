package cn.county.agrimach.web;

import cn.county.agrimach.domain.entity.DispatchSuggestion;
import cn.county.agrimach.domain.repo.Repos;
import cn.county.agrimach.service.ArchiveService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 共享接口（任意已登录角色，按本人/本社在服务层约束）：
 * 统一作业档案、派机建议明细、纠纷登记与查询。
 */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class ArchiveController {

    private final ArchiveService archiveService;
    private final Repos.SuggestionRepo suggestionRepo;

    /** 同一条作业档案：订单+分段版本+轨迹+异常+重算+签批+发票+维修+纠纷+证据链 */
    @GetMapping("/{id}/dossier")
    public Map<String, Object> dossier(@PathVariable Long id) {
        return archiveService.dossier(id);
    }

    /** 派机建议明细（评分解释） */
    @GetMapping("/{id}/suggestions")
    public List<DispatchSuggestion> suggestions(@PathVariable Long id) {
        return suggestionRepo.findByWorkOrderId(id);
    }

    /** 任意角色发起纠纷（农户/驾驶员/合作社），由村干部或合作社调解 */
    @PostMapping("/{id}/disputes")
    public Object raiseDispute(@PathVariable Long id, @RequestBody ArchiveService.DisputeReq req) {
        return archiveService.raiseDispute(id, req);
    }
}
