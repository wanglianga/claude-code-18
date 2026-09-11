#!/usr/bin/env python3
"""
雨后作业窗口重排接口验收（仅依赖 Python 标准库）。

覆盖：
1. 受阻扫描：土壤湿度超过机具进地阈值的当日作业单被识别（含湿度/降水/阈值证据）；
2. 重排计划：按土壤湿度、作物成熟紧迫度、农机位置、其他村可作业预约排序，
   给出 DIVERT（先转场）或 WAIT（原地等待）建议及空驶里程/油耗/满意度影响；
3. DIVERT：机具先转其他村可作业地块，受阻单顺延，额外空驶里程计入空驶段油耗与补贴、
   不向农户加收；受影响农户收到通知，记录“接受延期”；
4. WAIT：生成等待天气分段补贴与延期通知；农户“要求换机具”→自动改派高通过性机具；
   另一农户“取消作业”→订单与计划取消；
5. 通知结果计入合作社调度评分（换机/取消/接受延期/满意度/有效作业比例）；
6. 补贴申报中输出有效作业比例（转场空驶拉低该比例）。

前置：全新启动（docker compose up -d，演示数据）。
用法：BASE=http://host.docker.internal:3018 python3 tests/api_reroute_test.py
"""
import base64
import datetime
import json
import os
import sys
import urllib.error
import urllib.request

BASE = os.environ.get("BASE", "http://host.docker.internal:3018")
PWD = "123456"
DRIVERS = ["driver01", "driver02", "driver03", "driver04"]
TODAY = datetime.date.today()
D2 = (TODAY + datetime.timedelta(days=2)).isoformat()


def call(method, path, user=None, body=None):
    req = urllib.request.Request(
        BASE + path,
        data=json.dumps(body).encode() if body is not None else None,
        method=method)
    req.add_header("Content-Type", "application/json")
    if user:
        req.add_header("Authorization",
                       "Basic " + base64.b64encode(f"{user}:{PWD}".encode()).decode())
    try:
        with urllib.request.urlopen(req, timeout=25) as r:
            txt = r.read().decode()
            return r.status, (json.loads(txt) if txt else None)
    except urllib.error.HTTPError as e:
        txt = e.read().decode()
        try:
            return e.code, json.loads(txt)
        except Exception:
            return e.code, txt


def check(name, cond, detail=""):
    print(f"  [{'PASS' if cond else 'FAIL'}] {name} {detail}")
    if not cond:
        sys.exit(1)


def dispatch_blocked_order(lon, lat):
    """farmer03 在当日南山村（雨后受阻村）下单并派给丰收社 M-HV-01，驾驶员接单。"""
    st, o = call("POST", "/api/farmer/orders", "farmer03", {
        "cropType": "晚稻", "plotName": f"雨后重排测试-{lon}", "village": "南山村",
        "longitude": lon, "latitude": lat, "bookedAreaMu": 8.0,
        "operationType": "HARVESTING", "mudLevel": "HEAVY",
        "expectedStart": f"{TODAY} 09:00:00", "expectedEnd": f"{TODAY} 12:00:00",
        "strawRequested": False, "matureDaysLeft": 2})
    assert st == 200, (st, o)
    oid = o["id"]
    st, sugg = call("POST", f"/api/coop/orders/{oid}/suggestions", "coop")
    assert st == 200, (st, sugg)
    chosen = next(s for s in sugg if s["machine"]["code"] == "M-HV-01")
    st, _ = call("POST", f"/api/coop/orders/{oid}/dispatch", "coop",
                 {"suggestionId": chosen["id"], "opinion": "雨后重排测试派机"})
    assert st == 200, (st, _)
    who = None
    for dn in DRIVERS:
        st, _ = call("POST", f"/api/driver/orders/{oid}/accept", dn,
                     {"machineCondition": "正常", "departureFuelL": 110})
        if st == 200:
            who = dn
            break
    assert who, "无承接驾驶员"
    return oid, who


def notify_of(farmer, oid):
    st, notes = call("GET", "/api/farmer/notifications", farmer)
    assert st == 200
    return next(n for n in notes if n["workOrder"]["id"] == oid)


# ============================ 1. 受阻扫描 ============================
print("===== 1. 雨后土壤湿度受阻扫描（南山村 湿度92% > 收割机阈值90%） =====")
oid1, drv1 = dispatch_blocked_order(113.141, 28.291)
st, blocked = call("GET", f"/api/coop/reroute/blocked?date={TODAY}", "coop")
check("受阻单被识别", st == 200 and any(b["orderId"] == oid1 for b in blocked), str(blocked))
b1 = next(b for b in blocked if b["orderId"] == oid1)
check("受阻证据含湿度/降水/机具阈值",
      b1["soilMoisturePct"] == 92.0 and b1["rainfallMm"] == 18.0 and b1["thresholdPct"] == 90.0)
st, _ = call("POST", f"/api/coop/orders/{oid1}/reroute/plan", "coop2")
check("非所属合作社不能生成重排计划(403)", st == 403, f"http={st}")

# ============================ 2. DIVERT 转场 ============================
print("===== 2. 重排计划建议先转场双河村可作业地块（演示单1） =====")
st, plan = call("POST", f"/api/coop/orders/{oid1}/reroute/plan", "coop")
check("计划生成", st == 200, f"http={st}")
pid = plan["id"]
check("推荐 DIVERT", plan["recommendedAction"] == "DIVERT", plan["recommendationReason"])
check("首选候选为双河村演示单1", plan["bestAlternativeOrder"]["id"] == 1
      and plan["bestAlternativeOrder"]["village"] == "双河村")
check("转场额外空驶里程/分钟/油耗>0",
      plan["divertExtraKm"] > 0 and plan["divertExtraMinutes"] > 0 and plan["divertExtraFuelL"] > 0,
      f"{plan['divertExtraKm']}km/{plan['divertExtraMinutes']}分/+{plan['divertExtraFuelL']}L")
check("候选快照按距离+成熟紧迫度排序", "距机具" in plan["alternativesSnapshot"])

st, decided = call("POST", f"/api/coop/reroute/{pid}/decide", "coop",
                   {"action": "DIVERT", "note": "先抢收双河村成熟稻，再回南山村"})
check("执行转场", st == 200 and decided["status"] == "EXECUTED_DIVERT")
check("受阻单窗口顺延至后天", decided["rescheduledStart"].startswith(D2), decided["rescheduledStart"])

st, d1 = call("GET", f"/api/orders/{oid1}/dossier", "auditor")
latest = list(d1["segmentsByVersion"].values())[-1]
extra_haul = [s for s in latest if s["segmentType"] == "EMPTY_HAUL" and s.get("exceptionId")]
check("转场额外空驶段进入分段并给补贴", len(extra_haul) == 1 and extra_haul[0]["subsidyAmount"] > 0,
      f"补{extra_haul[0]['subsidyAmount'] if extra_haul else '无'}元")
check("转场绕行不向农户加收空驶费（基础里程<10km免费+异常空驶不计农户）",
      d1["order"]["emptyHaulFee"] == 0.0, f"空驶费{d1['order']['emptyHaulFee']}元")
st, alt_order = call("GET", "/api/coop/dispatch/pool", "coop")
check("候选演示单1已派给同一机具组", any(o["id"] == 1 and o["status"] == "DISPATCHED" for o in alt_order))
st, notes = call("GET", "/api/farmer/notifications", "farmer03")
n1 = next(n for n in notes if n["workOrder"]["id"] == oid1)
check("受阻农户收到延期通知", "顺延" in n1["content"] and n1["notifyStatus"] == "SENT")
st, notes01 = call("GET", "/api/farmer/notifications", "farmer01")
check("转场受益农户也收到通知", any(n["workOrder"]["id"] == 1 for n in notes01))
st, n1r = call("POST", f"/api/farmer/notifications/{n1['id']}/respond", "farmer03",
               {"response": "ACCEPT_DELAY", "note": "同意后天作业"})
check("记录农户接受延期", st == 200 and n1r["response"] == "ACCEPT_DELAY" and n1r["handled"] is False)
st, _ = call("POST", f"/api/farmer/notifications/{n1['id']}/respond", "farmer01") if False else (200, None)

# ============================ 3. WAIT + 要求换机具 ============================
print("===== 3. 无其他村候选时原地等待，农户要求换高通过性机具 =====")
oid2, drv2 = dispatch_blocked_order(113.143, 28.293)
st, plan2 = call("POST", f"/api/coop/orders/{oid2}/reroute/plan", "coop")
check("无候选推荐 WAIT", plan2["recommendedAction"] == "WAIT" and plan2["bestAlternativeOrder"] is None,
      plan2["recommendationReason"])
pid2 = plan2["id"]
st, decided2 = call("POST", f"/api/coop/reroute/{pid2}/decide", "coop",
                    {"action": "WAIT", "note": "晾墒等待，不转场"})
check("执行等待", st == 200 and decided2["status"] == "EXECUTED_WAIT")
st, d2 = call("GET", f"/api/orders/{oid2}/dossier", "auditor")
latest2 = list(d2["segmentsByVersion"].values())[-1]
wait_seg = [s for s in latest2 if s["segmentType"] == "WEATHER_WAIT"]
check("等待天气分段 480 分钟补贴 200 元（25元/h×8h）",
      len(wait_seg) == 1 and wait_seg[0]["durationMinutes"] == 480
      and abs(wait_seg[0]["subsidyAmount"] - 200.0) < 0.01)

n2 = notify_of("farmer03", oid2)
st, n2r = call("POST", f"/api/farmer/notifications/{n2['id']}/respond", "farmer03",
               {"response": "NEED_MACHINE_CHANGE", "note": "要求换履带通过性更好的收割机"})
check("记录要求换机具", st == 200 and n2r["response"] == "NEED_MACHINE_CHANGE")
st, handled = call("POST", f"/api/coop/reroute/notifications/{n2['id']}/change-machine", "coop")
check("合作社换机处置", st == 200 and handled["handled"] is True)
st, d2b = call("GET", f"/api/orders/{oid2}/dossier", "auditor")
check("改派后天可进地的他社履带收割机 M-HV-02（阈值82%<78%湿度）",
      d2b["order"]["machine"]["code"] == "M-HV-02", d2b["order"]["machine"]["code"])

# ============================ 4. 农户取消作业 ============================
print("===== 4. 另一受阻单农户选择取消作业 =====")
oid3, drv3 = dispatch_blocked_order(113.146, 28.296)
st, plan3 = call("POST", f"/api/coop/orders/{oid3}/reroute/plan", "coop")
check("仍推荐 WAIT", plan3["recommendedAction"] == "WAIT")
st, _ = call("POST", f"/api/coop/reroute/{plan3['id']}/decide", "coop",
             {"action": "WAIT", "note": "等待"})
check("等待决策执行", st == 200)
n3 = notify_of("farmer03", oid3)
st, n3r = call("POST", f"/api/farmer/notifications/{n3['id']}/respond", "farmer03",
               {"response": "CANCEL_ORDER", "note": "已自己人工收割，取消"})
check("记录取消作业", st == 200 and n3r["response"] == "CANCEL_ORDER")
st, _ = call("POST", f"/api/coop/reroute/notifications/{n3['id']}/cancel", "coop")
check("合作社确认取消", st == 200)
st, d3 = call("GET", f"/api/orders/{oid3}/dossier", "auditor")
check("订单与重排计划均置为取消", d3["order"]["status"] == "CANCELLED"
      and d3["reroutePlans"][0]["status"] == "CANCELLED")

# ============================ 5. 调度评分 ============================
print("===== 5. 通知结果计入合作社调度评分 =====")
st, score = call("GET", "/api/coop/reroute/score", "coop")
check("评分卡生成", st == 200 and "dispatchScore" in score)
print(f"  转场{score['divertCount']} 等待{score['waitCount']} 接受延期{score['acceptDelay']} "
      f"换机{score['machineChange']} 取消{score['cancel']} 满意度{score['satisfactionPoints']} "
      f"评分{score['dispatchScore']}({score['grade']})")
check("转场1等待2", score["divertCount"] == 1 and score["waitCount"] == 2)
check("换机1取消1接受延期1", score["machineChange"] == 1
      and score["cancel"] == 1 and score["acceptDelay"] >= 1)
check("换机/取消拉低调度评分(<90)", score["dispatchScore"] < 90, f"得分{score['dispatchScore']}")
check("评分含明细解释", len(score["breakdown"]) >= 4)

# ============================ 6. 结算转场单 → 补贴有效作业比例 ============================
print("===== 6. 转场受阻单结算后，补贴申报体现有效作业比例 =====")
st, _ = call("POST", f"/api/driver/orders/{oid1}/arrive", drv1)
st, _ = call("POST", f"/api/driver/orders/{oid1}/start", drv1)
st, _ = call("POST", f"/api/driver/orders/{oid1}/finish", drv1, {"returnFuelL": 80})
check("完工", st == 200, f"http={st}")
st, _ = call("POST", f"/api/farmer/orders/{oid1}/accept-work", "farmer03",
             {"actualAreaMu": 8.0, "rating": 4, "comment": "延期但完成"})
check("农户验收", st == 200)
st, _ = call("POST", f"/api/coop/orders/{oid1}/invoice", "coop")
check("开票", st == 200)
st, claim = call("POST", "/api/coop/subsidy/draft?period=2026-09-rr", "coop")
check("补贴草稿生成", st == 200, f"http={st} {claim if st != 200 else ''}")
ratio = claim["productiveRatioPct"]
check("申报输出有效作业比例且转场空驶使其<100%", 0 < ratio < 100, f"{ratio}%")
check("空驶补贴包含转场额外里程", claim["emptyHaulSubsidy"] > 0, f"空驶补{claim['emptyHaulSubsidy']}元")
st, d1b = call("GET", f"/api/orders/{oid1}/dossier", "auditor")
check("统一档案含重排计划", len(d1b["reroutePlans"]) == 1
      and d1b["reroutePlans"][0]["status"] == "EXECUTED_DIVERT")

print("\n雨后作业窗口重排全部场景验收通过")
