package cn.county.agrimach.service;

import cn.county.agrimach.domain.entity.*;
import cn.county.agrimach.domain.enums.E;
import cn.county.agrimach.domain.repo.Repos;
import cn.county.agrimach.service.support.TrackArea;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 地块面积争议复核：
 * 农户认为收费面积过高 → 归集预约面积/驾驶轨迹/卫星地块边界/驾驶员备注/农户验收照片五类证据；
 * 村干部裁决：
 *  - 确认超算：按复核面积版本化重算费用与油补、调减发票；
 *  - 确认少报：补计费用与油补，记农户诚信风险，后续预约须先做地块边界预确认；
 * 复核结果实时同步合作社结算与补贴申报（草稿自动重算、已申报挂审核附件并要求驳回重报），
 * 杜绝“农户费用已改、补贴面积仍按旧数据提交”。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AreaReviewService {

    private final Repos.AreaReviewRepo reviewRepo;
    private final Repos.OrderRepo orderRepo;
    private final Repos.TrackRepo trackRepo;
    private final Repos.InvoiceRepo invoiceRepo;
    private final Repos.ClaimRepo claimRepo;
    private final Repos.AttachmentRepo attachmentRepo;
    private final Repos.UserRepo userRepo;
    private final OrderService orderService;
    private final SubsidyService subsidyService;
    private final CurrentUser currentUser;
    private final ObjectMapper objectMapper;

    private static final double TOL = 0.05;

    public record RaiseReq(Double farmerClaimAreaMu, String reason, String acceptancePhotoRefs) {}
    public record DriverNoteReq(String driverNote) {}
    public record SatelliteReq(String satelliteBoundaryRef, List<List<Double>> boundaryCoords) {}
    public record DecideReq(Double reviewedAreaMu, String opinion,
                            String satelliteBoundaryRef, List<List<Double>> boundaryCoords) {}
    public record BoundaryPreConfirmReq(String plotName, String confirmRef) {}

    // ============================== 农户发起 ==============================

    @Transactional
    public AreaReview raise(Long orderId, RaiseReq r) {
        WorkOrder o = ownedOrder(orderId);
        if (o.getStatus() != E.OrderStatus.COMPLETED
                && o.getStatus() != E.OrderStatus.SETTLED
                && o.getStatus() != E.OrderStatus.INVOICED) {
            throw bad("作业完成并验收后才能发起面积争议复核，当前状态：" + o.getStatus().label);
        }
        if (r.farmerClaimAreaMu() == null || r.farmerClaimAreaMu() <= 0 || r.reason() == null) {
            throw bad("主张面积与争议理由必填");
        }
        if (reviewRepo.existsByWorkOrderIdAndStatus(orderId, E.AreaReviewStatus.OPEN)) {
            throw bad("该单已有待复核争议，请等待裁决");
        }
        AreaReview rv = new AreaReview();
        rv.setWorkOrder(o);
        rv.setRaisedBy(currentUser.get());
        rv.setFarmerClaimAreaMu(r.farmerClaimAreaMu());
        rv.setReason(r.reason());
        rv.setFarmerAcceptancePhotoRefs(
                r.acceptancePhotoRefs() != null ? r.acceptancePhotoRefs() : o.getFarmerAcceptancePhotoRefs());
        rv.setCalcVersionAtRaise(o.getCalcVersion());
        rv.setEvidenceSnapshot(snapshot(o, rv, null));
        return reviewRepo.save(rv);
    }

    // ============================== 证据补充 ==============================

    /** 驾驶员备注（仅本单承接驾驶员） */
    @Transactional
    public AreaReview driverNote(Long reviewId, DriverNoteReq r) {
        AreaReview rv = must(reviewId);
        ensureOpen(rv);
        UserAccount u = currentUser.get();
        if (rv.getWorkOrder().getDriver() == null
                || !rv.getWorkOrder().getDriver().getUser().getId().equals(u.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅本单承接驾驶员可补充备注");
        }
        rv.setDriverNote(r.driverNote());
        rv.setDriverNotedBy(u);
        rv.setDriverNotedAt(LocalDateTime.now());
        rv.setEvidenceSnapshot(snapshot(rv.getWorkOrder(), rv, null));
        return rv;
    }

    /** 卫星地块边界（合作社/村干部调取），自动核算卫星面积 */
    @Transactional
    public AreaReview satelliteBoundary(Long reviewId, SatelliteReq r) {
        AreaReview rv = must(reviewId);
        ensureOpen(rv);
        UserAccount u = currentUser.get();
        if (u.getRole() != E.Role.COOP && u.getRole() != E.Role.VILLAGE) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅合作社/村干部可录入卫星边界证据");
        }
        double[][] coords = r.boundaryCoords() == null ? new double[0][]
                : r.boundaryCoords().stream().map(p -> new double[]{p.get(0), p.get(1)}).toArray(double[][]::new);
        rv.setSatelliteBoundaryRef(r.satelliteBoundaryRef());
        rv.setSatelliteBoundaryAreaMu(TrackArea.polygonMuFromCoords(coords));
        rv.setEvidenceSnapshot(snapshot(rv.getWorkOrder(), rv, null));
        return rv;
    }

    // ============================== 村干部裁决 ==============================

    @Transactional
    public AreaReview decide(Long reviewId, DecideReq r) {
        AreaReview rv = must(reviewId);
        ensureOpen(rv);
        WorkOrder o = rv.getWorkOrder();
        UserAccount reviewer = currentUser.get();
        if (reviewer.getRole() != E.Role.VILLAGE) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅村干部可作出面积复核裁决");
        }
        if (r.reviewedAreaMu() == null || r.reviewedAreaMu() <= 0) {
            throw bad("复核核定面积必填");
        }
        if (r.satelliteBoundaryRef() != null) {
            rv.setSatelliteBoundaryRef(r.satelliteBoundaryRef());
            if (r.boundaryCoords() != null) {
                double[][] coords = r.boundaryCoords().stream()
                        .map(p -> new double[]{p.get(0), p.get(1)}).toArray(double[][]::new);
                rv.setSatelliteBoundaryAreaMu(TrackArea.polygonMuFromCoords(coords));
            }
        }
        double reviewed = round2(r.reviewedAreaMu());
        double areaBefore = nz(o.getActualAreaMu());
        double feeBefore = nz(o.getFarmerFee());
        double subBefore = nz(o.getSubsidyTotal());
        double booked = nz(o.getBookedAreaMu());

        E.AreaReviewStatus verdict;
        if (reviewed < areaBefore - TOL) {
            verdict = E.AreaReviewStatus.CONFIRMED_OVERCHARGE;
        } else if (reviewed > booked + TOL && reviewed > areaBefore + TOL) {
            verdict = E.AreaReviewStatus.CONFIRMED_UNDERREPORT;
        } else {
            verdict = E.AreaReviewStatus.REJECTED;
        }

        rv.setStatus(verdict);
        rv.setReviewedAreaMu(reviewed);
        rv.setReviewer(reviewer);
        rv.setReviewedAt(LocalDateTime.now());
        rv.setReviewOpinion(r.opinion());
        rv.setAreaBeforeMu(areaBefore);
        rv.setFeeBefore(feeBefore);
        rv.setSubsidyBefore(subBefore);

        if (verdict == E.AreaReviewStatus.REJECTED) {
            rv.setFeeAfter(feeBefore);
            rv.setSubsidyAfter(subBefore);
            rv.setCalcVersionAtReview(o.getCalcVersion());
            rv.setEvidenceSnapshot(snapshot(o, rv, verdict));
            return reviewRepo.save(rv);
        }

        // 超算退减 / 少报补计：核定面积成为最终收费面积，版本化重算费用与油补
        o.setReviewConfirmedAreaMu(reviewed);
        o.setActualAreaMu(reviewed);
        o.setAreaReviewAdjusted(true);
        var rec = orderService.applyCalc(o, null,
                "地块面积争议复核裁决（" + verdict.label + "）：核定" + reviewed
                        + "亩，原收费" + areaBefore + "亩，同步重算作业时间/费用/油补",
                "VILLAGE", false);
        double feeAfter = nz(o.getFarmerFee());
        double subAfter = nz(o.getSubsidyTotal());
        rv.setFeeAfter(feeAfter);
        rv.setSubsidyAfter(subAfter);
        rv.setCalcVersionAtReview(o.getCalcVersion());
        rv.setEvidenceSnapshot(snapshot(o, rv, verdict));

        // 已开票 → 发票调整（负数冲减 / 正数补开）
        invoiceRepo.findByWorkOrderId(o.getId()).ifPresent(inv -> {
            double delta = round2(feeAfter - feeBefore);
            inv.setAdjusted(true);
            inv.setAdjustmentAmount(delta);
            inv.setTotalAfterAdjustment(round2(nz(inv.getTotalAmount()) + delta));
            inv.setAdjustedAt(LocalDateTime.now());
            inv.setAdjustmentNote("面积争议复核 #" + rv.getId() + "：核定" + reviewed
                    + "亩，" + (delta < 0 ? "冲减退费" : "补计") + Math.abs(delta) + "元");
        });

        // 少报 → 诚信风险：后续预约须村干部先确认地块边界
        if (verdict == E.AreaReviewStatus.CONFIRMED_UNDERREPORT) {
            UserAccount farmer = o.getFarmer();
            farmer.setIntegrityRiskFlag(true);
            farmer.setIntegrityRiskCount(farmer.getIntegrityRiskCount() == null ? 1 : farmer.getIntegrityRiskCount() + 1);
            // 已持有的预确认凭据针对旧地块，置空待重新确认
            farmer.setPendingBoundaryRef(null);
            farmer.setPendingBoundaryPlot(null);
        }

        reviewRepo.save(rv);
        // 同步合作社结算（订单/发票已在上面更新）与补贴申报
        syncSubsidy(o, rv, feeBefore, feeAfter, subBefore, subAfter,
                rec != null ? rec.getVersion() : o.getCalcVersion());
        return rv;
    }

    /** 复核结果同步补贴申报：草稿自动重算；已申报挂附件并标记驳回重报；已核拨留调整单线索 */
    private void syncSubsidy(WorkOrder o, AreaReview rv, double feeBefore, double feeAfter,
                             double subBefore, double subAfter, int newVersion) {
        double subDelta = round2(subAfter - subBefore);
        for (SubsidyClaim claim : claimRepo.findByCoopIdOrderByCreatedAtDesc(o.getCoop().getId())) {
            boolean contains = claim.getItems().stream().anyMatch(i -> i.getWorkOrder().getId().equals(o.getId()));
            if (!contains) continue;

            SubsidyAttachment att = new SubsidyAttachment();
            att.setClaim(claim);
            att.setWorkOrder(o);
            att.setAreaReview(rv);
            att.setOldAreaMu(rv.getAreaBeforeMu());
            att.setNewAreaMu(rv.getReviewedAreaMu());
            att.setFeeDelta(round2(feeAfter - feeBefore));
            att.setSubsidyDelta(subDelta);
            att.setOldCalcVersion(rv.getCalcVersionAtRaise());
            att.setNewCalcVersion(newVersion);
            att.setEvidenceSnapshot(rv.getEvidenceSnapshot());

            switch (claim.getStatus()) {
                case DRAFT, REJECTED -> {
                    subsidyService.refreshOrderInClaim(claim, o.getId());
                    att.setHandlingNote("申报草稿已按复核核定面积自动重算（油补 "
                            + (subDelta < 0 ? "退减" : "补计") + Math.abs(subDelta) + " 元）");
                }
                case SUBMITTED -> {
                    claim.setNeedsAdjustment(true);
                    claim.setAdjustmentNote("作业单#" + o.getId() + " 经面积争议复核由 "
                            + rv.getAreaBeforeMu() + " 亩调整为 " + rv.getReviewedAreaMu()
                            + " 亩，油补差额 " + subDelta + " 元，请驳回后由合作社按新数据重报");
                    att.setHandlingNote("该单已在审核中，补贴仍按旧面积提交，须驳回重报；油补差额 " + subDelta + " 元");
                }
                case APPROVED -> att.setHandlingNote("补贴已核拨，需另行生成补贴调整单，油补差额 " + subDelta + " 元");
                default -> { }
            }
            claim.getAttachments().add(att);
            attachmentRepo.save(att);
        }
    }

    // ============================== 村干部边界预确认（诚信风险农户） ==============================

    @Transactional
    public UserAccount preConfirmBoundary(Long farmerId, BoundaryPreConfirmReq r) {
        if (currentUser.get().getRole() != E.Role.VILLAGE) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅村干部可作地块边界预确认");
        }
        UserAccount farmer = userRepo.findById(farmerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "农户不存在"));
        if (farmer.getRole() != E.Role.FARMER) throw bad("目标账号不是农户");
        farmer.setIntegrityRiskFlag(true); // 预确认本身针对风险农户，保持标记
        farmer.setPendingBoundaryPlot(r.plotName());
        farmer.setPendingBoundaryRef(r.confirmRef());
        return farmer;
    }

    // ============================== 查询与证据快照 ==============================

    @Transactional
    public List<AreaReview> listForOrder(Long orderId) {
        WorkOrder o = orderRepo.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "作业单不存在"));
        UserAccount u = currentUser.get();
        switch (u.getRole()) {
            case FARMER -> {
                if (!o.getFarmer().getId().equals(u.getId())) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅可查看本人预约单的复核");
                }
            }
            case DRIVER -> {
                if (o.getDriver() == null || !o.getDriver().getUser().getId().equals(u.getId())) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅可查看本人承接作业的复核");
                }
            }
            case COOP -> {
                if (o.getCoop() == null || !o.getCoop().getId().equals(u.getCoop().getId())) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅可查看本社作业的复核");
                }
            }
            default -> { } // 村干部、审核部门可全县查看
        }
        return reviewRepo.findByWorkOrderIdOrderByRaisedAtDesc(orderId);
    }

    private String snapshot(WorkOrder o, AreaReview rv, E.AreaReviewStatus verdict) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("workOrderId", o.getId());
        m.put("plot", o.getVillage() + " " + o.getPlotName());
        m.put("bookedAreaMu", o.getBookedAreaMu());
        m.put("chargedAreaMu", o.getActualAreaMu());
        m.put("trackAreaMu", o.getTrackAreaMu() != null ? o.getTrackAreaMu()
                : TrackArea.polygonMu(trackRepo.findByWorkOrderIdOrderBySeq(o.getId())));
        m.put("farmerClaimAreaMu", rv.getFarmerClaimAreaMu());
        m.put("farmerReason", rv.getReason());
        m.put("farmerAcceptancePhotoRefs", rv.getFarmerAcceptancePhotoRefs());
        m.put("driverNote", rv.getDriverNote());
        m.put("satelliteBoundaryRef", rv.getSatelliteBoundaryRef());
        m.put("satelliteBoundaryAreaMu", rv.getSatelliteBoundaryAreaMu());
        if (verdict != null) {
            m.put("verdict", verdict.label);
            m.put("reviewedAreaMu", rv.getReviewedAreaMu());
            m.put("reviewOpinion", rv.getReviewOpinion());
            m.put("reviewer", rv.getReviewer() != null ? rv.getReviewer().getDisplayName() : null);
        }
        try {
            return objectMapper.writeValueAsString(m);
        } catch (Exception e) {
            log.warn("证据快照序列化失败", e);
            return m.toString();
        }
    }

    private WorkOrder ownedOrder(Long orderId) {
        WorkOrder o = orderRepo.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "作业单不存在"));
        UserAccount u = currentUser.get();
        if (u.getRole() == E.Role.FARMER && !o.getFarmer().getId().equals(u.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅预约农户可发起争议复核");
        }
        return o;
    }

    private AreaReview must(Long id) {
        return reviewRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "复核单不存在"));
    }

    private void ensureOpen(AreaReview rv) {
        if (rv.getStatus() != E.AreaReviewStatus.OPEN) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "复核单已裁决：" + rv.getStatus().label);
        }
    }

    private static double nz(Double v) { return v == null ? 0 : v; }
    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }
}
