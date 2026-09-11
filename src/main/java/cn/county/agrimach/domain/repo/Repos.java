package cn.county.agrimach.domain.repo;

import cn.county.agrimach.domain.entity.*;
import cn.county.agrimach.domain.enums.E;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface Repos {

    interface UserRepo extends JpaRepository<UserAccount, Long> {
        Optional<UserAccount> findByUsername(String username);
        List<UserAccount> findByRole(E.Role role);
        List<UserAccount> findByCoop(Cooperative coop);
    }

    interface CoopRepo extends JpaRepository<Cooperative, Long> { }

    interface MachineRepo extends JpaRepository<Machine, Long> {
        List<Machine> findByCoopIdAndEnabledTrue(Long coopId);
        List<Machine> findByEnabledTrue();
    }

    interface DriverRepo extends JpaRepository<Driver, Long> {
        List<Driver> findByCoopId(Long coopId);
        Optional<Driver> findByUserId(Long userId);
    }

    interface OrderRepo extends JpaRepository<WorkOrder, Long> {
        List<WorkOrder> findByFarmerIdOrderByCreatedAtDesc(Long farmerId);
        List<WorkOrder> findByCoopIdOrderByCreatedAtDesc(Long coopId);
        List<WorkOrder> findByDriverId(Long driverId);
        List<WorkOrder> findByStatusIn(List<E.OrderStatus> statuses);
        List<WorkOrder> findTop50ByOrderByCreatedAtDesc();

        /** 某驾驶员在某日已有作业时间（疲劳/排班），按期望开始时间落在区间 [dayStart, dayEnd) 统计 */
        @Query("""
                select coalesce(sum(w.estimatedWorkMinutes),0) from WorkOrder w
                where w.driver.id = :driverId
                  and w.status in :active
                  and w.expectedStart >= :dayStart and w.expectedStart < :dayEnd
                """)
        Integer sumDriverMinutesOnDay(@Param("driverId") Long driverId,
                                      @Param("active") List<E.OrderStatus> active,
                                      @Param("dayStart") java.time.LocalDateTime dayStart,
                                      @Param("dayEnd") java.time.LocalDateTime dayEnd);
    }

    interface SuggestionRepo extends JpaRepository<DispatchSuggestion, Long> {
        List<DispatchSuggestion> findByWorkOrderId(Long orderId);
        void deleteByWorkOrderId(Long orderId);
    }

    interface RuleRepo extends JpaRepository<SubventionRule, Long> {
        Optional<SubventionRule> findByOperationTypeAndSegmentType(E.OperationType op, E.SegmentType seg);
        List<SubventionRule> findByOperationType(E.OperationType op);
    }

    interface SegmentRepo extends JpaRepository<WorkSegment, Long> {
        List<WorkSegment> findByWorkOrderIdOrderByStartTime(Long orderId);
        List<WorkSegment> findByWorkOrderIdAndCalcVersion(Long orderId, Integer version);
        void deleteByWorkOrderIdAndCalcVersionGreaterThan(Long orderId, Integer version);
    }

    interface TrackRepo extends JpaRepository<TrackPoint, Long> {
        List<TrackPoint> findByWorkOrderIdOrderBySeq(Long orderId);
        long countByWorkOrderId(Long orderId);
    }

    interface ExceptionRepo extends JpaRepository<WorkException, Long> {
        List<WorkException> findByWorkOrderId(Long orderId);
        List<WorkException> findByWorkOrderIdOrderByReportedAt(Long orderId);
        List<WorkException> findByState(E.ExceptionState state);
    }

    interface RecalcRepo extends JpaRepository<Recalculation, Long> {
        List<Recalculation> findByWorkOrderIdOrderByVersion(Long orderId);
    }

    interface SignoffRepo extends JpaRepository<Signoff, Long> {
        List<Signoff> findByWorkOrderId(Long orderId);
        Optional<Signoff> findByWorkOrderIdAndType(Long orderId, E.SignoffType type);
    }

    interface InvoiceRepo extends JpaRepository<Invoice, Long> {
        Optional<Invoice> findByWorkOrderId(Long orderId);
        boolean existsByInvoiceNo(String invoiceNo);
    }

    interface MaintenanceRepo extends JpaRepository<MaintenanceRecord, Long> {
        List<MaintenanceRecord> findByMachineIdOrderByWindowStartDesc(Long machineId);
        List<MaintenanceRecord> findByMachineCoopIdOrderByWindowStartDesc(Long coopId);
        List<MaintenanceRecord> findByStatus(E.RepairStatus status);
    }

    interface DisputeRepo extends JpaRepository<Dispute, Long> {
        List<Dispute> findByWorkOrderId(Long orderId);
        List<Dispute> findByStatus(E.DisputeStatus status);
    }

    interface ClaimRepo extends JpaRepository<SubsidyClaim, Long> {
        List<SubsidyClaim> findByCoopIdOrderByCreatedAtDesc(Long coopId);
        List<SubsidyClaim> findByStatus(E.SubsidyStatus status);
        boolean existsByClaimNo(String claimNo);
    }

    interface WeatherRepo extends JpaRepository<WeatherRecord, Long> {
        Optional<WeatherRecord> findByVillageAndDate(String village, String date);
        List<WeatherRecord> findByVillageOrderByDate(String village);
    }
}
