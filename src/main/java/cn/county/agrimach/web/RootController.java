package cn.county.agrimach.web;

import cn.county.agrimach.domain.enums.E;
import cn.county.agrimach.domain.repo.Repos;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** 根路由与元数据（无需登录，便于健康探活与枚举字典查询） */
@RestController
@RequiredArgsConstructor
public class RootController {

    private final Repos.UserRepo userRepo;

    @GetMapping("/")
    public Map<String, Object> root() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("service", "县域农机跨村预约作业与油补核算服务");
        m.put("status", "UP");
        m.put("userCount", userRepo.count());
        m.put("docs", Map.of(
                "farmer", "/api/farmer/orders",
                "driver", "/api/driver/orders",
                "coop", "/api/coop/dispatch/pool",
                "village", "/api/village/orders/{id}/confirm",
                "auditor", "/api/auditor/claims",
                "dossier", "/api/orders/{id}/dossier"));
        return m;
    }

    @GetMapping("/api/meta/enums")
    public Map<String, Object> enums() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("operationType", labels(E.OperationType.values()));
        m.put("machineType", labels(E.MachineType.values()));
        m.put("mudLevel", labels(E.MudLevel.values()));
        m.put("weather", labels(E.WeatherType.values()));
        m.put("exceptionType", labels(E.ExceptionType.values()));
        m.put("segmentType", labels(E.SegmentType.values()));
        m.put("orderStatus", labels(E.OrderStatus.values()));
        return m;
    }

    private Map<String, String> labels(Enum<?>[] values) {
        Map<String, String> m = new LinkedHashMap<>();
        Arrays.stream(values).forEach(v -> {
            try {
                Object label = v.getClass().getField("label").get(v);
                m.put(v.name(), String.valueOf(label));
            } catch (ReflectiveOperationException e) {
                m.put(v.name(), v.name());
            }
        });
        return m;
    }
}
