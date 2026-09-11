package cn.county.agrimach.domain.enums;

/**
 * 领域枚举统一持有（接口嵌套枚举隐式 public static，跨包可访问）。
 */
public interface E {

    /** 系统角色 */
    enum Role {
        FARMER("农户"), DRIVER("驾驶员"), COOP("合作社"), VILLAGE("村干部"), AUDITOR("补贴审核部门");
        public final String label;
        Role(String label) { this.label = label; }
    }

    /** 农机类型 */
    enum MachineType {
        TRACTOR("拖拉机"), HARVESTER("联合收割机"), RICE_TRANSPLANTER("水稻插秧机"),
        DRONE("植保无人机"), BALER("秸秆打捆机");
        public final String label;
        MachineType(String label) { this.label = label; }
    }

    /** 作业类型 */
    enum OperationType {
        PLOWING("耕地"), ROTOTILLING("旋耕"), HARVESTING("机收"),
        TRANSPLANTING("插秧"), PLANT_PROTECTION("植保"), STRAW_TREATMENT("秸秆处理");
        public final String label;
        OperationType(String label) { this.label = label; }
    }

    /** 泥泞程度 */
    enum MudLevel {
        LIGHT("轻度"), MEDIUM("中度"), HEAVY("重度泥泞");
        public final String label;
        MudLevel(String label) { this.label = label; }
    }

    /** 天气状况 */
    enum WeatherType {
        SUNNY("晴"), CLOUDY("多云"), OVERCAST("阴"), LIGHT_RAIN("小雨"), RAIN("中雨"), STORM("暴雨");
        public final String label;
        WeatherType(String label) { this.label = label; }
        public boolean isRain() { return this == LIGHT_RAIN || this == RAIN || this == STORM; }
    }

    /** 作业单状态 */
    enum OrderStatus {
        SUBMITTED("已预约待派机"), DISPATCHED("已派机待接单"), ACCEPTED("已接单"),
        EN_ROUTE("空驶赴地"), ARRIVED("已到地"), IN_PROGRESS("作业中"),
        INTERRUPTED("异常中断待处理"), COMPLETED("作业完成待验收"),
        SETTLED("农户已验收"), INVOICED("已开票结算"), CANCELLED("已取消");
        public final String label;
        OrderStatus(String label) { this.label = label; }
    }

    /**
     * 油补核算分段类型（补贴与实际服务严格对应，分段核算）：
     * 空驶 / 有效作业 / 等待天气 / 返工 / 机具故障
     */
    enum SegmentType {
        EMPTY_HAUL("空驶"), PRODUCTIVE("有效作业"), WEATHER_WAIT("等待天气"),
        REWORK("返工"), MACHINE_FAULT("机具故障");
        public final String label;
        SegmentType(String label) { this.label = label; }
    }

    /** 五类现场异常 → 触发重排重算 */
    enum ExceptionType {
        UNCLEAR_BOUNDARY("地块边界不清"),
        AREA_EXCEEDED("实际面积大于预约"),
        RAIN_BLOCKED("雨后无法进地"),
        MACHINE_FAULT("机具故障"),
        FARMER_ADDON("农户临时增加作业");
        public final String label;
        ExceptionType(String label) { this.label = label; }
    }

    enum ExceptionState { OPEN, RESOLVED }

    /** 三方签批：村干部确认 / 农户签字 / 合作社调度意见 */
    enum SignoffType {
        VILLAGE_CONFIRM("村干部确认"), FARMER_SIGN("农户签字"), COOP_DISPATCH("合作社调度意见");
        public final String label;
        SignoffType(String label) { this.label = label; }
    }

    enum SignoffStatus { PENDING, APPROVED, REJECTED }

    enum DisputeStatus { OPEN("处理中"), RESOLVED("已化解");
        public final String label;
        DisputeStatus(String label) { this.label = label; }
    }

    enum SubsidyStatus { DRAFT("待提交"), SUBMITTED("已申报待审核"), APPROVED("审核通过"), REJECTED("已驳回");
        public final String label;
        SubsidyStatus(String label) { this.label = label; }
    }

    enum RepairStatus { PLANNED("计划维修窗口"), IN_PROGRESS("维修中"), DONE("维修完成");
        public final String label;
        RepairStatus(String label) { this.label = label; }
    }

    /** 地块面积争议复核结果 */
    enum AreaReviewStatus {
        OPEN("待复核"),
        CONFIRMED_OVERCHARGE("确认超算-应退减"),
        CONFIRMED_UNDERREPORT("确认农户少报-记诚信风险"),
        REJECTED("复核驳回-原核算无误");
        public final String label;
        AreaReviewStatus(String label) { this.label = label; }
    }
}
