package cn.county.agrimach.service;

import cn.county.agrimach.domain.entity.*;
import cn.county.agrimach.domain.enums.E;
import cn.county.agrimach.domain.repo.Repos;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一作业档案：把农户验收、轨迹面积、油耗、发票、维修记录、纠纷处理、
 * 三方签批、异常重算、分段油补汇入同一条作业记录；
 * 合作社、驾驶员、农户、补贴审核部门围绕同一档案协同，任何费用调整都可追到
 * 地块、轨迹与现场验收证据。
 */
@Service
@RequiredArgsConstructor
public class ArchiveService {

    private final Repos.OrderRepo orderRepo;
    private final Repos.SegmentRepo segmentRepo;
    private final Repos.TrackRepo trackRepo;
    private final Repos.ExceptionRepo exceptionRepo;
    private final Repos.RecalcRepo recalcRepo;
    private final Repos.SignoffRepo signoffRepo;
    private final Repos.InvoiceRepo invoiceRepo;
    private final Repos.MaintenanceRepo maintenanceRepo;
    private final Repos.DisputeRepo disputeRepo;
    private final Repos.AreaReviewRepo areaReviewRepo;
    private final Repos.AttachmentRepo attachmentRepo;
    private final Repos.ReroutePlanRepo reroutePlanRepo;
    private final CurrentUser currentUser;

    public Map<String, Object> dossier(Long orderId) {
        WorkOrder o = orderRepo.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "作业单不存在"));
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("order", o);
        d.put("farmer", Map.of("id", o.getFarmer().getId(), "name", o.getFarmer().getDisplayName(),
                "village", nz(o.getFarmer().getVillage())));
        d.put("cooperative", o.getCoop() == null ? null : Map.of(
                "id", o.getCoop().getId(), "name", o.getCoop().getName(), "baseVillage", o.getCoop().getBaseVillage()));
        d.put("driver", o.getDriver() == null ? null : Map.of(
                "id", o.getDriver().getId(), "name", o.getDriver().getName(),
                "license", nz(o.getDriver().getLicenseNo()), "qualifications", nz(o.getDriver().getQualifications())));
        d.put("machine", o.getMachine() == null ? null : Map.of(
                "id", o.getMachine().getId(), "code", o.getMachine().getCode(),
                "name", o.getMachine().getName(), "type", o.getMachine().getType().label));
        d.put("segmentsByVersion", segmentsByVersion(orderId));
        d.put("trackPoints", trackRepo.findByWorkOrderIdOrderBySeq(orderId));
        d.put("trackAreaMu", o.getTrackAreaMu());
        d.put("exceptions", exceptionRepo.findByWorkOrderIdOrderByReportedAt(orderId));
        d.put("recalculations", recalcRepo.findByWorkOrderIdOrderByVersion(orderId));
        d.put("signoffs", signoffRepo.findByWorkOrderId(orderId));
        d.put("invoice", invoiceRepo.findByWorkOrderId(orderId).orElse(null));
        d.put("maintenance", maintenanceRepo.findAll().stream()
                .filter(m -> m.getWorkOrder() != null && m.getWorkOrder().getId().equals(orderId)).toList());
        d.put("disputes", disputeRepo.findByWorkOrderId(orderId));
        d.put("areaReviews", areaReviewRepo.findByWorkOrderIdOrderByRaisedAtDesc(orderId));
        d.put("subsidyAttachments", attachmentRepo.findByWorkOrderId(orderId));
        d.put("reroutePlans", reroutePlanRepo.findByBlockedOrderIdOrderByCreatedAtDesc(orderId));
        d.put("evidenceChain", evidenceChain(orderId));
        return d;
    }

    /** 按费用版本归档分段，审核时可逐版核对每次重排 */
    private Map<Integer, List<WorkSegment>> segmentsByVersion(Long orderId) {
        Map<Integer, List<WorkSegment>> map = new LinkedHashMap<>();
        for (WorkSegment s : segmentRepo.findByWorkOrderIdOrderByStartTime(orderId)) {
            map.computeIfAbsent(s.getCalcVersion(), k -> new java.util.ArrayList<>()).add(s);
        }
        return map;
    }

    /** 证据链：每次费用调整 → 触发异常 → 现场证据 → 重算对照 → 分段 */
    private List<Map<String, Object>> evidenceChain(Long orderId) {
        return recalcRepo.findByWorkOrderIdOrderByVersion(orderId).stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("version", r.getVersion());
            m.put("reason", r.getReason());
            m.put("triggerRole", r.getTriggerRole());
            m.put("createdAt", r.getCreatedAt());
            m.put("area", Map.of("old", r.getOldAreaMu(), "new", r.getNewAreaMu()));
            m.put("workMinutes", Map.of("old", r.getOldWorkMinutes(), "new", r.getNewWorkMinutes()));
            m.put("farmerFee", Map.of("old", r.getOldFarmerFee(), "new", r.getNewFarmerFee()));
            m.put("subsidy", Map.of("old", r.getOldSubsidy(), "new", r.getNewSubsidy()));
            if (r.getWorkException() != null) {
                WorkException ex = r.getWorkException();
                m.put("exception", Map.of(
                        "type", ex.getType().label,
                        "description", ex.getDescription(),
                        "evidence", nz(ex.getEvidence()),
                        "reportedBy", ex.getReportedBy().getDisplayName(),
                        "reportedAt", ex.getReportedAt(),
                        "resolution", nz(ex.getResolution())));
            }
            m.put("segments", r.getSegmentDeltaText());
            return m;
        }).toList();
    }

    // ============================== 纠纷 ==============================

    public record DisputeReq(String reason) {}

    @Transactional
    public Dispute raiseDispute(Long orderId, DisputeReq r) {
        WorkOrder o = orderRepo.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "作业单不存在"));
        Dispute d = new Dispute();
        d.setWorkOrder(o);
        d.setRaisedBy(currentUser.get());
        d.setReason(r.reason());
        return disputeRepo.save(d);
    }

    public record ResolveDisputeReq(String resolution) {}

    @Transactional
    public Dispute resolveDispute(Long disputeId, ResolveDisputeReq r) {
        Dispute d = disputeRepo.findById(disputeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "纠纷不存在"));
        d.setStatus(E.DisputeStatus.RESOLVED);
        d.setMediator(currentUser.get());
        d.setResolution(r.resolution());
        d.setResolvedAt(LocalDateTime.now());
        return d;
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
