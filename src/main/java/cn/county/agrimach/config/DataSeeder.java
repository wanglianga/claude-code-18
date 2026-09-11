package cn.county.agrimach.config;

import cn.county.agrimach.domain.entity.*;
import cn.county.agrimach.domain.enums.E;
import cn.county.agrimach.domain.repo.Repos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 演示数据：两个跨村合作社、机具、五类角色账号、油补规则、天气与示例预约单 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder {

    private final Repos.UserRepo userRepo;
    private final Repos.CoopRepo coopRepo;
    private final Repos.MachineRepo machineRepo;
    private final Repos.DriverRepo driverRepo;
    private final Repos.RuleRepo ruleRepo;
    private final Repos.WeatherRepo weatherRepo;
    private final Repos.OrderRepo orderRepo;
    private final PasswordEncoder encoder;

    @Value("${app.demo.enabled:true}")
    private boolean demoEnabled;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seed() {
        if (!demoEnabled || userRepo.count() > 0) {
            log.info("演示数据跳过(enabled={}, users={})", demoEnabled, userRepo.count());
            return;
        }
        String pwd = encoder.encode("123456");

        // ---------------- 合作社 ----------------
        Cooperative c1 = coopRepo.save(new Cooperative("丰收农机专业合作社", "河东镇",
                113.100, 28.250, "王调度", "13800000001"));
        Cooperative c2 = coopRepo.save(new Cooperative("金穗农机服务合作社", "河西村",
                113.060, 28.230, "陈社长", "13800000002"));

        // ---------------- 账号 ----------------
        user(c1, "coop", "王调度", E.Role.COOP, "河东镇", pwd);
        user(c2, "coop2", "陈社长", E.Role.COOP, "河西村", pwd);
        UserAccount auditor = user(null, "auditor", "刘审核（县农业农村局）", E.Role.AUDITOR, "县城", pwd);
        UserAccount vcad = user(null, "village01", "周村长", E.Role.VILLAGE, "双河村", pwd);

        UserAccount f1 = user(null, "farmer01", "张农户", E.Role.FARMER, "双河村", pwd);
        f1.setPhone("13900000001");
        UserAccount f2 = user(null, "farmer02", "钱农户", E.Role.FARMER, "南山村", pwd);
        UserAccount f3 = user(null, "farmer03", "孙农户", E.Role.FARMER, "南山村", pwd);

        // ---------------- 驾驶员 + 账号 ----------------
        driver(c1, "driver01", "李建国", "驾驶证A证-农机G2", "HARVESTER,TRACTOR,BALER", pwd);
        driver(c1, "driver02", "赵大军", "农机G1证", "TRACTOR,RICE_TRANSPLANTER", pwd);
        driver(c1, "driver03", "孙志强", "农机G2证-植保操作证", "HARVESTER,DRONE", pwd);
        driver(c2, "driver04", "李长贵", "农机G2证", "HARVESTER,TRACTOR,BALER", pwd);

        // ---------------- 机具 ----------------
        machine(c1, "M-HV-01", "履带式联合收割机1号", E.MachineType.HARVESTER,
                "HARVESTING,STRAW_TREATMENT", 8.0, 22.0, 20.0, 0.0, 200.0, 90.0);
        machine(c1, "M-TR-01", "大马力拖拉机1号", E.MachineType.TRACTOR,
                "PLOWING,ROTOTILLING", 8.0, 18.0, 25.0, 196.0, 200.0, 75.0);
        machine(c1, "M-RT-01", "高速水稻插秧机1号", E.MachineType.RICE_TRANSPLANTER,
                "TRANSPLANTING", 6.0, 12.0, 25.0, 40.0, 200.0, 78.0);
        machine(c1, "M-DR-01", "植保无人机1号", E.MachineType.DRONE,
                "PLANT_PROTECTION", 40.0, 3.0, 35.0, 12.0, 150.0, 100.0);
        machine(c1, "M-BL-01", "秸秆打捆机1号", E.MachineType.BALER,
                "STRAW_TREATMENT", 10.0, 15.0, 20.0, 60.0, 200.0, 80.0);
        machine(c2, "M-HV-02", "联合收割机2号", E.MachineType.HARVESTER,
                "HARVESTING,STRAW_TREATMENT", 7.5, 21.0, 20.0, 30.0, 200.0, 82.0);
        machine(c2, "M-TR-02", "拖拉机2号", E.MachineType.TRACTOR,
                "PLOWING,ROTOTILLING", 7.5, 17.0, 25.0, 55.0, 200.0, 75.0);

        // ---------------- 油补规则（每种作业 × 五类分段） ----------------
        double[] productiveRate = {12, 12, 15, 18, 5, 10};
        int i = 0;
        for (E.OperationType op : E.OperationType.values()) {
            rule(op, E.SegmentType.PRODUCTIVE, "MU", productiveRate[i++], 1.0, true, "有效作业按亩补贴");
            rule(op, E.SegmentType.EMPTY_HAUL, "KM", 3.0, 1.0, true, "跨村空驶按里程补贴");
            rule(op, E.SegmentType.WEATHER_WAIT, "HOUR", 25.0, 1.0, true, "雨后等待天气按小时补贴");
            rule(op, E.SegmentType.REWORK, "MU", 8.0, 1.0, true, "非农户原因返工按亩补贴");
            rule(op, E.SegmentType.MACHINE_FAULT, "HOUR", 20.0, 0.5, true, "机具故障停机补贴下浮50%");
        }

        // ---------------- 天气（演示派机遇雨顺延、雨后窗口重排） ----------------
        LocalDate today = LocalDate.now();
        weather("双河村", today, E.WeatherType.SUNNY, 0.0, 55.0, false, "适宜机械进地");
        weather("南山村", today, E.WeatherType.RAIN, 18.0, 92.0, true, "土壤过湿，轮式机械不宜进地");
        weather("南山村", today.plusDays(1), E.WeatherType.LIGHT_RAIN, 6.0, 87.0, true, "仍需晾晒");
        weather("南山村", today.plusDays(2), E.WeatherType.CLOUDY, 0.0, 78.0, false, "可进地");
        weather("河东镇", today, E.WeatherType.SUNNY, 0.0, 52.0, false, "");
        weather("河西村", today, E.WeatherType.CLOUDY, 0.0, 60.0, false, "");

        // ---------------- 示例预约单 ----------------
        WorkOrder demo1 = new WorkOrder();
        demo1.setFarmer(f1);
        demo1.setCropType("晚稻");
        demo1.setPlotName("双河村东塘畈3号田");
        demo1.setVillage("双河村");
        demo1.setLongitude(113.118);
        demo1.setLatitude(28.258);
        demo1.setBookedAreaMu(12.0);
        demo1.setOperationType(E.OperationType.HARVESTING);
        demo1.setMudLevel(E.MudLevel.MEDIUM);
        demo1.setExpectedStart(today.atTime(9, 0));
        demo1.setExpectedEnd(today.atTime(12, 0));
        demo1.setStrawRequested(true);
        demo1.setRemark("演示单：稻子已熟，要求收割+秸秆打捆");
        demo1.setStatus(E.OrderStatus.SUBMITTED);
        orderRepo.save(demo1);

        WorkOrder demo2 = new WorkOrder();
        demo2.setFarmer(f2);
        demo2.setPlotName("南山村坡里湾8号田");
        demo2.setCropType("晚稻");
        demo2.setVillage("南山村");
        demo2.setLongitude(113.140);
        demo2.setLatitude(28.290);
        demo2.setBookedAreaMu(8.0);
        demo2.setOperationType(E.OperationType.HARVESTING);
        demo2.setMudLevel(E.MudLevel.HEAVY);
        demo2.setExpectedStart(today.atTime(14, 0));
        demo2.setExpectedEnd(today.atTime(17, 0));
        demo2.setStrawRequested(false);
        demo2.setRemark("演示单：今日有中雨，派机建议应顺延至后天多云");
        demo2.setStatus(E.OrderStatus.SUBMITTED);
        orderRepo.save(demo2);

        log.info("演示数据写入完成: 合作社2 机具7 账号11 油补规则30 天气6 预约单2（统一密码 123456）");
    }

    private UserAccount user(Cooperative coop, String username, String name, E.Role role, String village, String pwd) {
        UserAccount u = new UserAccount(username, pwd, name, role);
        u.setCoop(coop);
        u.setVillage(village);
        return userRepo.save(u);
    }

    private void driver(Cooperative coop, String username, String name, String license, String quals, String pwd) {
        UserAccount u = user(coop, username, name, E.Role.DRIVER, coop.getBaseVillage(), pwd);
        u.setPhone("13" + username.substring(username.length() - 2) + "0000" + username.substring(username.length() - 2));
        Driver d = new Driver();
        d.setUser(u);
        d.setCoop(coop);
        d.setName(name);
        d.setLicenseNo(license);
        d.setQualifications(quals);
        driverRepo.save(d);
    }

    private void machine(Cooperative coop, String code, String name, E.MachineType type, String ops,
                         double rate, double fuel, double speed, double hourMeter, double interval,
                         double maxSoilMoisture) {
        Machine m = new Machine();
        m.setCoop(coop);
        m.setCode(code);
        m.setName(name);
        m.setType(type);
        m.setSupportedOps(ops);
        m.setWorkRateMuPerHour(rate);
        m.setHourlyFuelL(fuel);
        m.setRoadSpeedKmh(speed);
        m.setHourMeter(hourMeter);
        m.setLastMaintenanceHour(Math.max(0, hourMeter - interval * 0.3));
        m.setMaintenanceIntervalHours(interval);
        m.setMaxSoilMoisturePct(maxSoilMoisture);
        machineRepo.save(m);
    }

    private void rule(E.OperationType op, E.SegmentType seg, String basis, double rate,
                      double factor, boolean payable, String note) {
        SubventionRule r = new SubventionRule();
        r.setOperationType(op);
        r.setSegmentType(seg);
        r.setBasis(basis);
        r.setRate(rate);
        r.setFactor(factor);
        r.setPayable(payable);
        r.setNote(note);
        ruleRepo.save(r);
    }

    private void weather(String village, LocalDate date, E.WeatherType w, double rain,
                         double soilMoisture, boolean blocked, String advisory) {
        WeatherRecord wr = new WeatherRecord();
        wr.setVillage(village);
        wr.setDate(date.toString());
        wr.setWeather(w);
        wr.setRainfallMm(rain);
        wr.setSoilMoisturePct(soilMoisture);
        wr.setMachineAccessBlocked(blocked);
        wr.setAdvisory(advisory);
        weatherRepo.save(wr);
    }
}
