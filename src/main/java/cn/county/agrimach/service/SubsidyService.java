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
import java.util.*;

/**
 * 补贴申报与审核：
 * 合作社把跨村多单按分段（空驶/有效作业/等待天气/返工/机具故障）汇总成申报材料；
 * 审核部门每一条明细都能下钻到作业档案、重算版本、轨迹与现场证据。
 */
@Service
@RequiredArgsConstructor
public class SubsidyService {

    private final Repos.ClaimRepo claimRepo;
    private final Repos.OrderRepo orderRepo;
    private final Repos.SegmentRepo segmentRepo;
    private final Repos.RuleRepo ruleRepo;
    private final CurrentUser currentUser;

    @Transactional
    public SubsidyClaim createDraft(Long coopId, String period) {
        // 已在未结/已通过申请中出现过的作业不再重复申报
        Set<Long> claimedOrderIds = new HashSet<>();
        for (SubsidyClaim c : claimRepo.findByCoopIdOrderByCreatedAtDesc(coopId)) {
            if (c.getStatus() != E.SubsidyStatus.REJECTED) {
                c.getItems().forEach(i -> claimedOrderIds.add(i.getWorkOrder().getId()));
            }
        }

        List<WorkOrder> orders = orderRepo.findByCoopIdOrderByCreatedAtDesc(coopId).stream()
                .filter(o -> o.getStatus() == E.OrderStatus.SETTLED || o.getStatus() == E.OrderStatus.INVOICED)
                .filter(o -> !claimedOrderIds.contains(o.getId()))
                .filter(o -> nz(o.getSubsidyTotal()) > 0)
                .toList();
        if (orders.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "本周期暂无可申报的已结算作业");

        SubsidyClaim claim = new SubsidyClaim();
        claim.setClaimNo(generateClaimNo(period));
        claim.setCoop(orders.get(0).getCoop());
        claim.setPeriod(period);
        claim.setStatus(E.SubsidyStatus.DRAFT);

        for (WorkOrder o : orders) {
            int latestVersion = o.getCalcVersion();
            Map<E.SegmentType, double[]> agg = new EnumMap<>(E.SegmentType.class);
            // double[]{areaMu, distanceKm, minutes, amount}
            for (WorkSegment s : segmentRepo.findByWorkOrderIdAndCalcVersion(o.getId(), latestVersion)) {
                double[] a = agg.computeIfAbsent(s.getSegmentType(), k -> new double[4]);
                a[0] += s.getAreaMu();
                a[1] += s.getDistanceKm();
                a[2] += s.getDurationMinutes();
                a[3] += s.getSubsidyAmount();
            }
            for (Map.Entry<E.SegmentType, double[]> en : agg.entrySet()) {
                E.SegmentType type = en.getKey();
                double[] a = en.getValue();
                if (a[3] <= 0) continue;
                SubventionRule rule = ruleRepo.findByOperationTypeAndSegmentType(o.getOperationType(), type).orElse(null);
                String basis = rule != null ? rule.getBasis() : "HOUR";
                double qty = switch (basis) {
                    case "MU" -> r2(a[0]);
                    case "KM" -> r2(a[1]);
                    default -> r2(a[2] / 60.0);
                };
                SubsidyClaimItem item = new SubsidyClaimItem();
                item.setClaim(claim);
                item.setWorkOrder(o);
                item.setSegmentType(type);
                item.setQuantity(qty);
                item.setBasis(basis);
                item.setRate(qty > 0 ? r2(a[3] / qty) : 0);
                item.setAmount(r2(a[3]));
                item.setCalcVersion(latestVersion);
                claim.getItems().add(item);
            }
        }

        summarize(claim);
        claim.setOrderCount((int) claim.getItems().stream().map(i -> i.getWorkOrder().getId()).distinct().count());
        return claimRepo.save(claim);
    }

    /**
     * 面积争议复核裁决后，把申报草稿中该作业单的全部明细按最新费用版本重建并重算汇总，
     * 保证补贴面积与农户费用同步、不按旧数据提交。
     */
    @Transactional
    public void refreshOrderInClaim(SubsidyClaim claim, Long orderId) {
        WorkOrder o = orderRepo.findById(orderId).orElse(null);
        if (o == null) return;
        claim.getItems().removeIf(i -> i.getWorkOrder().getId().equals(orderId));

        int latestVersion = o.getCalcVersion();
        Map<E.SegmentType, double[]> agg = new EnumMap<>(E.SegmentType.class);
        for (WorkSegment s : segmentRepo.findByWorkOrderIdAndCalcVersion(orderId, latestVersion)) {
            double[] a = agg.computeIfAbsent(s.getSegmentType(), k -> new double[4]);
            a[0] += s.getAreaMu();
            a[1] += s.getDistanceKm();
            a[2] += s.getDurationMinutes();
            a[3] += s.getSubsidyAmount();
        }
        for (Map.Entry<E.SegmentType, double[]> en : agg.entrySet()) {
            E.SegmentType type = en.getKey();
            double[] a = en.getValue();
            if (a[3] <= 0) continue;
            SubventionRule rule = ruleRepo.findByOperationTypeAndSegmentType(o.getOperationType(), type).orElse(null);
            String basis = rule != null ? rule.getBasis() : "HOUR";
            double qty = switch (basis) {
                case "MU" -> r2(a[0]);
                case "KM" -> r2(a[1]);
                default -> r2(a[2] / 60.0);
            };
            SubsidyClaimItem item = new SubsidyClaimItem();
            item.setClaim(claim);
            item.setWorkOrder(o);
            item.setSegmentType(type);
            item.setQuantity(qty);
            item.setBasis(basis);
            item.setRate(qty > 0 ? r2(a[3] / qty) : 0);
            item.setAmount(r2(a[3]));
            item.setCalcVersion(latestVersion);
            claim.getItems().add(item);
        }
        summarize(claim);
        claim.setOrderCount((int) claim.getItems().stream()
                .map(i -> i.getWorkOrder().getId()).distinct().count());
        claimRepo.save(claim);
    }

    private void summarize(SubsidyClaim claim) {
        double empty = 0, prod = 0, wait = 0, rework = 0, fault = 0;
        for (SubsidyClaimItem i : claim.getItems()) {
            switch (i.getSegmentType()) {
                case EMPTY_HAUL -> empty += i.getAmount();
                case PRODUCTIVE -> prod += i.getAmount();
                case WEATHER_WAIT -> wait += i.getAmount();
                case REWORK -> rework += i.getAmount();
                case MACHINE_FAULT -> fault += i.getAmount();
            }
        }
        claim.setEmptyHaulSubsidy(r2(empty));
        claim.setProductiveSubsidy(r2(prod));
        claim.setWeatherWaitSubsidy(r2(wait));
        claim.setReworkSubsidy(r2(rework));
        claim.setFaultSubsidy(r2(fault));
        claim.setTotalSubsidy(r2(empty + prod + wait + rework + fault));
    }

    @Transactional
    public SubsidyClaim submit(Long claimId) {
        SubsidyClaim c = must(claimId);
        if (c.getStatus() != E.SubsidyStatus.DRAFT && c.getStatus() != E.SubsidyStatus.REJECTED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅草稿/驳回状态可提交");
        }
        c.setStatus(E.SubsidyStatus.SUBMITTED);
        c.setSubmittedAt(LocalDateTime.now());
        return c;
    }

    public record ReviewReq(boolean approved, String comment) {}

    @Transactional
    public SubsidyClaim review(Long claimId, ReviewReq r) {
        SubsidyClaim c = must(claimId);
        if (c.getStatus() != E.SubsidyStatus.SUBMITTED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅已申报材料可审核");
        }
        if (r.approved() && c.isNeedsAdjustment()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "该申报存在面积争议复核后未按新数据重报的作业单（" + c.getAdjustmentNote()
                            + "），请先驳回，待合作社重报后再审");
        }
        c.setStatus(r.approved() ? E.SubsidyStatus.APPROVED : E.SubsidyStatus.REJECTED);
        c.setReviewedBy(currentUser.get());
        c.setReviewedAt(LocalDateTime.now());
        c.setReviewComment(r.comment());
        return c;
    }

    public List<SubsidyClaim> listForReview() {
        return claimRepo.findByStatus(E.SubsidyStatus.SUBMITTED);
    }

    private SubsidyClaim must(Long id) {
        return claimRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "补贴申请不存在"));
    }

    private String generateClaimNo(String period) {
        String base = "BT" + period.replace("-", "");
        String no;
        int seq = 1;
        do {
            no = base + "-" + String.format("%03d", seq++);
        } while (claimRepo.existsByClaimNo(no));
        return no;
    }

    private static double nz(Double v) { return v == null ? 0 : v; }
    private static double r2(double v) { return Math.round(v * 100) / 100.0; }
}
