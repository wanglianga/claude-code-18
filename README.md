# 县域农机跨村预约作业与油补核算服务

面向县域农机作业场景的一体化服务：农户跨村预约 → 按机具类型/合作社排班/驾驶员资质/道路距离/天气/油补规则生成派机建议 → 驾驶员接单与作业留痕 → 五类现场异常重排重算 → 合作社跨村路线与补贴申报 → 审核部门下钻证据链审核。

技术栈：Java 21 + Spring Boot 3.3 + Spring Security + Spring Data JPA + PostgreSQL 16，Maven 多阶段 Docker 构建。

## 原始需求

> 开发县域农机跨村预约作业与油补核算服务，可采用 Java、Spring Boot 和 PostgreSQL。农户提交作物类型、地块位置、预计面积、作业类型、泥泞程度、期望时间和是否需要秸秆处理后，服务根据农机类型、合作社排班、驾驶员资质、道路距离、天气和油料补贴规则生成派机建议。驾驶员接单后，需要确认机具状态、出发油量、到地时间、实际作业轨迹、作业面积和农户验收。若地块边界不清、实际面积大于预约、雨后无法进地、机具故障或农户临时要求增加作业，服务要重新计算作业时间、费用和油补。合作社查看的不是单个订单，而是跨村路线、机具利用率、驾驶员疲劳、维修窗口和补贴申报材料。作业结束后，农户验收、轨迹面积、油耗、发票、维修记录和纠纷处理会进入同一条作业档案；油补核算要区分空驶、有效作业、等待天气、返工和机具故障，避免补贴和实际服务脱节。服务还要把合作社、驾驶员、农户和补贴审核部门放在同一条作业记录里，任何费用调整都能追到地块、轨迹和现场验收证据。服务还要把村干部确认、农户签字和合作社调度意见纳入档案，补贴审核时可以解释每次重排原因。

## 一键启动（宿主 docker compose）

```bash
cp .env.example .env        # 可按需要改 CC_PUBLISH_PORT（默认 3018）
docker compose up -d --build
docker compose ps           # app 与 db 均 healthy
```

- 仅把应用端口发布到宿主：`${CC_PUBLISH_PORT}:8080`；**PostgreSQL 不发布宿主端口**，只在 compose 内网通过服务名 `db` 访问。
- 健康检查：`GET /actuator`（镜像内置 HEALTHCHECK，非 root 用户运行）。
- 停止并清理：`docker compose down`（加 `-v` 同时删除数据库卷）。

实际映射端口以以下命令为准（避免硬编码端口冲突）：

```bash
docker compose port app 8080
# 访问： http://host.docker.internal:<映射端口>/
```

## 测试账号（演示密码统一为 `123456`，HTTP Basic Auth）

| 角色 | 用户名 | 密码 | 权限/能做什么 |
|---|---|---|---|
| 合作社（丰收社/河东镇） | `coop` | 123456 | 看待派机池、生成/采纳派机建议、处置异常重算、跨村路线/利用率/疲劳/维修窗口、开票、补贴申报 |
| 合作社（金穗社/河西村） | `coop2` | 123456 | 第二家合作社，数据按社隔离 |
| 驾驶员 | `driver01` | 123456 | 李建国，资质 HARVESTER/TRACTOR/BALER：接单（机具状态+出发油量）、到地、上传轨迹、上报异常、完工 |
| 驾驶员 | `driver02` / `driver03` | 123456 | 赵大军（拖拉机/插秧机）、孙志强（收割机/无人机） |
| 驾驶员 | `driver04` | 123456 | 金穗社李长贵（收割机/拖拉机/打捆机） |
| 农户 | `farmer01` | 123456 | 张农户（双河村）：提交预约、查看本人单据、最终验收签字 |
| 农户 | `farmer02` / `farmer03` | 123456 | 钱农户/孙农户（南山村） |
| 村干部 | `village01` | 123456 | 周村长：边界丈量现场确认、纠纷调解 |
| 补贴审核部门 | `auditor` | 123456 | 县农业农村局刘审核：审查申报材料、通过/驳回，可下钻作业档案 |

启动时自动写入演示数据：2 家合作社、7 台机具、11 个账号、30 条油补规则（6 作业类型 × 5 分段）、6 条天气预报、2 张待派机预约单（其中南山村单遇中雨，派机建议会自动顺延）。

## 端到端业务流（接口走查）

Base 地址以 `docker compose port app 8080` 为准，下面用 `$BASE` 表示。所有写接口需 Basic Auth。

```bash
# 1) 农户提交预约（作物/地块经纬度/面积/作业类型/泥泞/期望时间/秸秆处理）
curl -u farmer01:123456 -X POST "$BASE/api/farmer/orders" -H 'Content-Type: application/json' -d '{
  "cropType":"晚稻","plotName":"东塘畈3号田","village":"双河村",
  "longitude":113.118,"latitude":28.258,"bookedAreaMu":12,
  "operationType":"HARVESTING","mudLevel":"MEDIUM",
  "expectedStart":"2026-09-11 09:00:00","expectedEnd":"2026-09-11 12:00:00",
  "strawRequested":true,"remark":"收割+秸秆打捆"}'

# 2) 合作社生成派机建议（机具类型/资质/距离/天气顺延/疲劳评分），取首条 id
curl -u coop:123456 -X POST "$BASE/api/coop/orders/1/suggestions"

# 3) 合作社采纳建议派机（自动落首版费用/油补 + 三方签批占位）
curl -u coop:123456 -X POST "$BASE/api/coop/orders/1/dispatch" -H 'Content-Type: application/json' \
  -d '{"suggestionId":1,"opinion":"同意按最优建议调度，注意泥泞降速"}'

# 4) 驾驶员接单：确认机具状态 + 出发油量
curl -u driver01:123456 -X POST "$BASE/api/driver/orders/1/accept" -H 'Content-Type: application/json' \
  -d '{"machineCondition":"割台、履带正常，机油水温正常","departureFuelL":120}'
curl -u driver01:123456 -X POST "$BASE/api/driver/orders/1/arrive"
curl -u driver01:123456 -X POST "$BASE/api/driver/orders/1/start"

# 5) 上传作业轨迹（闭环多边形，完工时自动核算轨迹面积）
curl -u driver01:123456 -X POST "$BASE/api/driver/orders/1/track" -H 'Content-Type: application/json' -d '[
 {"seq":1,"longitude":113.1180,"latitude":28.2580,"speedKmh":2,"working":true},
 {"seq":2,"longitude":113.1192,"latitude":28.2581,"speedKmh":3,"working":true},
 {"seq":3,"longitude":113.1191,"latitude":28.2570,"speedKmh":3,"working":true},
 {"seq":4,"longitude":113.1179,"latitude":28.2569,"speedKmh":2,"working":true}]'

# 6) 现场异常（示例：实际面积大于预约）→ 作业中断
curl -u driver01:123456 -X POST "$BASE/api/driver/orders/1/exceptions" -H 'Content-Type: application/json' -d '{
  "type":"AREA_EXCEEDED","description":"实打实测13.8亩，比预约多1.8亩",
  "evidence":"现场测量照片IMG_2201;农户在场","measuredAreaMu":13.8}'

# 7) 合作社处置异常 → 自动重算作业时间/费用/油补并生成 v2 留痕
curl -u coop:123456 -X POST "$BASE/api/coop/exceptions/1/resolve" -H 'Content-Type: application/json' -d '{
  "measuredAreaMu":13.8,"resolution":"按实测面积续作，超面积按追加单价"}'

# 8) 完工（返航油量→实际油耗）→ 农户验收签字 → 合作社开票
curl -u driver01:123456 -X POST "$BASE/api/driver/orders/1/finish" -H 'Content-Type: application/json' -d '{"returnFuelL":86}'
curl -u farmer01:123456 -X POST "$BASE/api/farmer/orders/1/accept-work" -H 'Content-Type: application/json' \
  -d '{"actualAreaMu":13.8,"rating":5,"comment":"收割干净，按时完成"}'
curl -u coop:123456 -X POST "$BASE/api/coop/orders/1/invoice"

# 9) 统一作业档案（任意角色可查，证据链逐版解释重排原因）
curl -u auditor:123456 "$BASE/api/orders/1/dossier"

# 10) 合作社补贴申报（跨村多单 × 五分段汇总）→ 审核部门审核
curl -u coop:123456 -X POST "$BASE/api/coop/subsidy/draft?period=2026-09"
curl -u coop:123456 -X POST "$BASE/api/coop/subsidy/1/submit"
curl -u auditor:123456 "$BASE/api/auditor/claims"
curl -u auditor:123456 -X POST "$BASE/api/auditor/claims/1/review" -H 'Content-Type: application/json' \
  -d '{"approved":true,"comment":"分段与轨迹、验收证据一致，准予补贴"}'
```

### 五类异常与重算口径

| 异常 | 上报量化字段 | 重算影响 | 必备签批 |
|---|---|---|---|
| 边界不清 `UNCLEAR_BOUNDARY` | `measuredAreaMu` | 按村干部共同丈量的实测面积修正费用/时间 | **村干部确认**后才能处置 |
| 实际面积超预约 `AREA_EXCEEDED` | `measuredAreaMu` | 超出面积按追加单价；有效作业时间增加 | 农户验收签字 |
| 雨后无法进地 `RAIN_BLOCKED` | `downtimeMinutes`、`extraEmptyHaulKm` | 新增等待天气段补贴；折返/再赴地空驶给补贴、不重复收农户空驶费 | 调度意见记录顺延 |
| 机具故障 `MACHINE_FAULT` | `downtimeMinutes`、`reworkAreaMu`、`extraEmptyHaulKm` | 故障停机段（补贴下浮50%）+ 返工段 + 换机空驶；自动开维修窗口；不增加农户费用 | 维修记录入档 |
| 农户临时追加 `FARMER_ADDON` | `addedOperation`、`addedAreaMu` | 新增作业类型的有效作业段（追加单价） | 农户验收追认 |

### 油补五分段（补贴锚定实际服务）

`空驶 EMPTY_HAUL`（元/km，含跨村赴地与异常折返/换机）、`有效作业 PRODUCTIVE`（元/亩）、`等待天气 WEATHER_WAIT`（元/小时）、`返工 REWORK`（元/亩，非农户原因）、`机具故障 MACHINE_FAULT`（元/小时，系数 0.5 下浮）。规则在 `subvention_rule` 表按“作业类型 × 分段类型”配置，每次核算结果按费用版本写入 `work_segment`。

## 合作社调度视图（不是单个订单）

- `GET /api/coop/dispatch/pool`：调度池。**归属隔离规则**：未分派（SUBMITTED）预约单为全县共享池，所有合作社可见并竞价派机；订单派给某社后（DISPATCHED）仅该社在池中可见，其他合作社（如 `coop2`）看不到合作社 1 的订单，且对已派单再次生成建议/派机会被拒绝（4xx）。非合作社角色访问返回 403。
- `GET /api/coop/routes?date=yyyy-MM-dd`：按驾驶员串接当日跨村作业链（驻地→村 A→村 B→回社），逐段道路里程、到地时间。
- `GET /api/coop/utilization`：机具当日工时/日上限利用率、按小时台账的到保预警。
- `GET /api/coop/fatigue`：驾驶员当日累计工时/疲劳阈值，≥85% 高风险、≥100% 强制休息。
- `GET /api/coop/maintenance`：故障登记维修单 + 保养间隔预警窗口。
- `POST /api/coop/subsidy/draft?period=2026-09`：跨村多单按五分段生成申报材料草稿。

## 统一作业档案与证据链

`GET /api/orders/{id}/dossier` 在同一条记录中汇聚：订单与派机信息、按版本归档的油补分段、轨迹点与轨迹面积、异常工单、逐版重算（面积/工时/农户费/油补前后对照）、村干部确认/农户签字/合作社调度意见三方签批、发票、维修记录、纠纷处理，以及 `evidenceChain`（每次费用调整 → 触发异常 → 现场证据 → 分段明细），保证补贴审核能解释每次重排原因。

## 主要接口一览

| 角色 | 方法与路径 |
|---|---|
| 农户 | `POST /api/farmer/orders`、`GET /api/farmer/orders`、`POST /api/farmer/orders/{id}/exceptions`、`POST /api/farmer/orders/{id}/accept-work` |
| 驾驶员 | `POST .../accept`、`/arrive`、`/start`、`/track`、`/exceptions`、`/finish`；`GET /api/driver/orders` |
| 合作社 | `GET /api/coop/dispatch/pool`、`POST /api/coop/orders/{id}/suggestions`、`/dispatch`、`POST /api/coop/exceptions/{id}/resolve`、`/routes`、`/utilization`、`/fatigue`、`/maintenance`、`/orders/{id}/invoice`、`/orders/{id}/opinion`、`/subsidy/draft`、`/subsidy/{id}/submit` |
| 村干部 | `POST /api/village/orders/{id}/confirm`、`POST /api/village/disputes/{id}/resolve` |
| 审核部门 | `GET /api/auditor/claims`、`POST /api/auditor/claims/{id}/review` |
| 共享 | `GET /api/orders/{id}/dossier`、`GET /api/orders/{id}/suggestions`、`POST /api/orders/{id}/disputes` |
| 公共 | `GET /`、`GET /api/meta/enums`、`GET /actuator/health` |

## 验证方式（宿主 docker compose up）

本工程的验收标准是“compose up 健康 + 关键业务流可通过接口走通”，不依赖在本机安装 JDK/Maven：构建所需工具链全部由多阶段 Dockerfile（`maven:3.9-eclipse-temurin-21` → `eclipse-temurin:21-jre`）提供。启动后请按上文“端到端业务流”依次调用，或直接访问 `GET /` 确认服务状态。

接口级自动化验收（仅依赖 Python 标准库，服务 `compose up` 后运行）：

```bash
# 合作社归属隔离：非合作社角色 403；coop2 调度池不含合作社1已派订单
BASE=http://host.docker.internal:3018 python3 tests/api_pool_isolation_test.py
```

## 目录结构

```
src/main/java/cn/county/agrimach/
├── config/        # Security 配置、DB 用户体系、演示数据种子
├── domain/
│   ├── enums/     # 全部领域枚举（角色/机具/作业/五分段/五异常/签批/补贴状态…）
│   ├── entity/    # JPA 实体（作业单为贯穿核心）
│   └── repo/      # Spring Data 仓储
├── service/
│   ├── support/   # Geo 距离、定价目录、核算引擎 CalcEngine、分段计划 PlanBuilder、轨迹面积
│   ├── OrderService.java      # 预约/派机建议/接单作业/异常重算/验收（版本化留痕）
│   ├── CoopService.java       # 跨村路线/利用率/疲劳/维修窗口
│   ├── SubsidyService.java    # 补贴申报与审核
│   ├── ArchiveService.java    # 统一作业档案 + 纠纷
│   └── DocumentService.java   # 发票与三方签批
└── web/           # 五类角色 REST 控制器 + 共享档案接口
```
