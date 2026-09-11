package cn.county.agrimach.service;

import cn.county.agrimach.domain.entity.Invoice;
import cn.county.agrimach.domain.entity.Signoff;
import cn.county.agrimach.domain.entity.WorkOrder;
import cn.county.agrimach.domain.enums.E;
import cn.county.agrimach.domain.repo.Repos;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;

/** 开票与三方签批（村干部确认 / 农户签字 / 合作社调度意见） */
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final Repos.OrderRepo orderRepo;
    private final Repos.InvoiceRepo invoiceRepo;
    private final Repos.SignoffRepo signoffRepo;
    private final CurrentUser currentUser;

    @Transactional
    public Invoice issueInvoice(Long orderId) {
        WorkOrder o = must(orderId);
        if (o.getStatus() != E.OrderStatus.SETTLED && o.getStatus() != E.OrderStatus.INVOICED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "农户验收后才能开票: " + o.getStatus().label);
        }
        return invoiceRepo.findByWorkOrderId(orderId).orElseGet(() -> {
            double workFee = nz(o.getFarmerFee()) - nz(o.getEmptyHaulFee()) - nz(o.getWaitingFee());
            Invoice inv = new Invoice();
            inv.setWorkOrder(o);
            inv.setInvoiceNo(generateInvoiceNo(o.getId()));
            inv.setWorkFee(r2(workFee));
            inv.setEmptyHaulFee(nz(o.getEmptyHaulFee()));
            inv.setWaitingFee(nz(o.getWaitingFee()));
            double total = r2(workFee + nz(o.getEmptyHaulFee()) + nz(o.getWaitingFee()));
            inv.setTotalAmount(total);
            inv.setTaxAmount(r2(total * inv.getTaxRate()));
            inv.setTitle("农机作业费-" + o.getFarmer().getDisplayName());
            invoiceRepo.save(inv);
            o.setInvoiceNo(inv.getInvoiceNo());
            o.setInvoicedAt(LocalDateTime.now());
            o.setStatus(E.OrderStatus.INVOICED);
            return inv;
        });
    }

    private String generateInvoiceNo(Long orderId) {
        String no;
        do {
            no = "FP" + java.time.LocalDate.now().toString().replace("-", "")
                    + String.format("%04d", orderId) + (int) (Math.random() * 90 + 10);
        } while (invoiceRepo.existsByInvoiceNo(no));
        return no;
    }

    public record VillageConfirmReq(boolean approved, String opinion) {}

    /** 村干部现场确认（边界丈量、面积争议等关键调整必须项） */
    @Transactional
    public Signoff villageConfirm(Long orderId, VillageConfirmReq r) {
        WorkOrder o = must(orderId);
        Signoff s = signoffRepo.findByWorkOrderIdAndType(orderId, E.SignoffType.VILLAGE_CONFIRM)
                .orElseGet(() -> signoffRepo.save(new Signoff(o, E.SignoffType.VILLAGE_CONFIRM)));
        s.setStatus(r.approved() ? E.SignoffStatus.APPROVED : E.SignoffStatus.REJECTED);
        s.setSigner(currentUser.get());
        s.setOpinion(r.opinion());
        s.setSignedAt(LocalDateTime.now());
        return s;
    }

    public record CoopOpinionReq(String opinion) {}

    /** 合作社调度意见（重排时追加说明，补贴审核需解释每次重排原因） */
    @Transactional
    public Signoff coopOpinion(Long orderId, CoopOpinionReq r) {
        WorkOrder o = must(orderId);
        Signoff s = signoffRepo.findByWorkOrderIdAndType(orderId, E.SignoffType.COOP_DISPATCH)
                .orElseGet(() -> signoffRepo.save(new Signoff(o, E.SignoffType.COOP_DISPATCH)));
        s.setStatus(E.SignoffStatus.APPROVED);
        s.setSigner(currentUser.get());
        s.setOpinion(r.opinion());
        s.setSignedAt(LocalDateTime.now());
        return s;
    }

    private WorkOrder must(Long id) {
        return orderRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "作业单不存在"));
    }
    private static double nz(Double v) { return v == null ? 0 : v; }
    private static double r2(double v) { return Math.round(v * 100) / 100.0; }
}
