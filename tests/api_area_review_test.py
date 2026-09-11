#!/usr/bin/env python3
"""
地块面积争议复核接口验收（仅依赖 Python 标准库）。

覆盖：
A. 超算：农户发起复核（五类证据归集）→ 驾驶员备注 → 卫星边界 → 村干部裁决
   → 费用与油补同步退减、发票冲红调整、补贴申报草稿自动按新面积重算，
   复核证据作为附件进入补贴审核，杜绝“费用已改、补贴按旧面积提交”。
B. 少报：裁决核定面积大于预约/收费面积 → 记诚信风险 → 后续预约无边界预确认被拦截，
   村干部预确认后方可预约。
C. 已申报场景：复核裁决后申报单标记 needsAdjustment，审核通过被系统拦截，
   必须驳回 → 合作社按新数据重新申报 → 复审通过。

前置：全新启动（docker compose up -d，演示数据）。
用法：BASE=http://host.docker.internal:3018 python3 tests/api_area_review_test.py
"""
import base64
import json
import os
import sys
import time
import urllib.error
import urllib.request

BASE = os.environ.get("BASE", "http://host.docker.internal:3018")
PWD = "123456"
DRIVERS = ["driver01", "driver02", "driver03", "driver04"]


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


def square_poly(lon, lat, area_mu):
    """以 (lon,lat) 为左下点，按目标亩数生成近似正方形闭合多边形坐标。"""
    side_m = (area_mu * 666.6667) ** 0.5
    dlon = side_m / 98000.0  # 纬度约28°处 1° 经度≈98km
    dlat = side_m / 111000.0
    return [[lon, lat], [lon + dlon, lat + dlat * 0.12],
            [lon + dlon * 0.9, lat - dlat], [lon - dlon * 0.08, lat - dlat * 1.05]]


def settle_order(farmer, village, lon, lat, area, mud, period_day, photos=None, actual=None):
    """提交→派机→接单→到地→开工→完工→验收→开票，返回 (order, driver)。"""
    st, o = call("POST", "/api/farmer/orders", farmer, {
        "cropType": "晚稻", "plotName": f"复核测试田-{period_day}", "village": village,
        "longitude": lon, "latitude": lat, "bookedAreaMu": area,
        "operationType": "HARVESTING", "mudLevel": mud,
        "expectedStart": f"2026-09-{period_day} 09:00:00",
        "expectedEnd": f"2026-09-{period_day} 12:00:00",
        "strawRequested": False})
    assert st == 200, (st, o)
    oid = o["id"]
    st, sugg = call("POST", f"/api/coop/orders/{oid}/suggestions", "coop")
    assert st == 200, (st, sugg)
    st, _ = call("POST", f"/api/coop/orders/{oid}/dispatch", "coop",
                 {"suggestionId": sugg[0]["id"], "opinion": "复核流程测试派机"})
    assert st == 200, (st, _)
    who = None
    for dn in DRIVERS:
        st, _ = call("POST", f"/api/driver/orders/{oid}/accept", dn,
                     {"machineCondition": "正常", "departureFuelL": 100})
        if st == 200:
            who = dn
            break
    assert who, "无承接驾驶员"
    call("POST", f"/api/driver/orders/{oid}/arrive", who)
    call("POST", f"/api/driver/orders/{oid}/start", who)
    poly = square_poly(lon, lat, actual or area)
    call("POST", f"/api/driver/orders/{oid}/track", who, [
        {"seq": 1, "longitude": poly[0][0], "latitude": poly[0][1], "speedKmh": 2, "working": True},
        {"seq": 2, "longitude": poly[1][0], "latitude": poly[1][1], "speedKmh": 3, "working": True},
        {"seq": 3, "longitude": poly[2][0], "latitude": poly[2][1], "speedKmh": 3, "working": True},
        {"seq": 4, "longitude": poly[3][0], "latitude": poly[3][1], "speedKmh": 2, "working": True}])
    st, fin = call("POST", f"/api/driver/orders/{oid}/finish", who, {"returnFuelL": 70})
    assert st == 200, (st, fin)
    st, _ = call("POST", f"/api/farmer/orders/{oid}/accept-work", farmer,
                 {"actualAreaMu": actual or area, "rating": 4, "comment": "待复核",
                  "acceptancePhotoRefs": photos})
    assert st == 200, (st, _)
    st, _ = call("POST", f"/api/coop/orders/{oid}/invoice", "coop")
    assert st == 200, (st, _)
    return oid, who

# ============================ A. 确认超算 → 退减同步 ============================
print("===== A. 超算复核：收费10亩，农户主张8.5亩，裁决8.5亩 =====")
oidA, drvA = settle_order("farmer03", "双河村", 113.120, 28.260, 10.0, "MEDIUM", 15,
                          photos="PHOTO-A-1,PHOTO-A-2")
st, before = call("GET", f"/api/orders/{oidA}/dossier", "auditor")
fee_before, sub_before = before["order"]["farmerFee"], before["order"]["subsidyTotal"]
print(f"  结算: 收费面积10亩 农户费{fee_before} 油补{sub_before}")

st, claim = call("POST", "/api/coop/subsidy/draft?period=2026-09-a", "coop")
check("补贴草稿生成（旧面积）", st == 200 and claim["totalSubsidy"] > 0,
      f"合计{claim['totalSubsidy']} {claim['orderCount']}单")
claim_id = claim["id"]
prod_before = claim["productiveSubsidy"]

st, rv = call("POST", f"/api/farmer/orders/{oidA}/area-reviews", "farmer03", {
    "farmerClaimAreaMu": 8.5, "reason": "实打实收只有8.5亩，收费按10亩偏高",
    "acceptancePhotoRefs": "PHOTO-A-1,PHOTO-A-2"})
check("农户发起复核", st == 200 and rv["status"] == "OPEN", f"http={st}")
rid = rv["id"]
check("证据快照含预约/轨迹/验收照片", all(k in rv["evidenceSnapshot"] for k in
      ["bookedAreaMu", "trackAreaMu", "farmerAcceptancePhotoRefs"]))

st, _ = call("POST", f"/api/driver/area-reviews/{rid}/note", drvA,
             {"driverNote": "作业中目测约8-9亩，西边低洼田未计入可作业区"})
check("承接驾驶员备注", st == 200)
st, _ = call("POST", f"/api/driver/area-reviews/{rid}/note", "driver02", {"driverNote": "越权"})
check("非承接驾驶员备注被拒", st == 403, f"http={st}")

st, _ = call("POST", f"/api/coop/area-reviews/{rid}/satellite", "coop",
             {"satelliteBoundaryRef": "SAT-BOUNDARY-A-9",
              "boundaryCoords": square_poly(113.120, 28.260, 8.5)})
check("合作社录入卫星地块边界", st == 200)

st, _ = call("POST", f"/api/village/area-reviews/{rid}/decide", "driver01",
             {"reviewedAreaMu": 8.5, "opinion": "越权裁决"})
check("驾驶员不能裁决(403)", st == 403, f"http={st}")

st, decided = call("POST", f"/api/village/area-reviews/{rid}/decide", "village01", {
    "reviewedAreaMu": 8.5,
    "opinion": "结合卫星边界、轨迹与现场丈量，核定8.5亩，确认超算1.5亩，应予退减",
    "satelliteBoundaryRef": "SAT-BOUNDARY-A-9",
    "boundaryCoords": square_poly(113.120, 28.260, 8.5)})
check("村干部裁决超算", st == 200 and decided["status"] == "CONFIRMED_OVERCHARGE", f"http={st}")
print(f"  裁决: 费 {decided['feeBefore']}→{decided['feeAfter']} "
      f"油补 {decided['subsidyBefore']}→{decided['subsidyAfter']}")
check("费用同步退减", decided["feeAfter"] < decided["feeBefore"] - 1)
check("油补同步退减", decided["subsidyAfter"] < decided["subsidyBefore"] - 1)
check("复核证据含五类来源与裁决", all(k in decided["evidenceSnapshot"] for k in
      ["bookedAreaMu", "trackAreaMu", "satelliteBoundaryAreaMu", "driverNote",
       "farmerAcceptancePhotoRefs", "verdict"]))

st, dA = call("GET", f"/api/orders/{oidA}/dossier", "auditor")
check("订单标记复核调整", dA["order"]["areaReviewAdjusted"] is True
      and abs(dA["order"]["actualAreaMu"] - 8.5) < 0.01)
check("统一档案含复核单", len(dA["areaReviews"]) == 1 and dA["areaReviews"][0]["status"] == "CONFIRMED_OVERCHARGE")
inv = dA["invoice"]
check("发票冲减调整", inv["adjusted"] is True and inv["adjustmentAmount"] < 0
      and abs(inv["totalAfterAdjustment"] - decided["feeAfter"]) < 0.02,
      f"调整额{inv['adjustmentAmount']} 调整后合计{inv['totalAfterAdjustment']}")

st, refreshed = call("POST", f"/api/coop/subsidy/{claim_id}/submit", "coop")
check("草稿提交", st == 200)
st, claim_detail = call("GET", f"/api/auditor/claims/{claim_id}", "auditor")
atts = claim_detail.get("attachments", [])
check("复核证据成为补贴审核附件", len(atts) == 1 and atts[0]["areaReview"]["id"] == rid)
check("附件标注草稿已自动重算", "自动重算" in atts[0]["handlingNote"])
prod_after = claim_detail["productiveSubsidy"]
check("补贴面积同步退减（作业补贴下调）", prod_after < prod_before - 1,
      f"{prod_before}→{prod_after}")
check("草稿重算后无需驳回标记", claim_detail["needsAdjustment"] is False)
st, rev = call("POST", f"/api/auditor/claims/{claim_id}/review", "auditor",
               {"approved": True, "comment": "复核附件齐全，按8.5亩核拨"})
check("审核通过", st == 200 and rev["status"] == "APPROVED")

# ============================ B. 确认少报 → 诚信风险 + 预确认 ============================
print("===== B. 少报复核：预约/收费6亩，卫星核定7.2亩 =====")
oidB, drvB = settle_order("farmer02", "双河村", 113.125, 28.262, 6.0, "LIGHT", 16,
                          photos="PHOTO-B-1", actual=6.0)
st, rvB = call("POST", f"/api/farmer/orders/{oidB}/area-reviews", "farmer02", {
    "farmerClaimAreaMu": 5.5, "reason": "觉得没有6亩", "acceptancePhotoRefs": "PHOTO-B-1"})
ridB = rvB["id"]
st, decB = call("POST", f"/api/village/area-reviews/{ridB}/decide", "village01", {
    "reviewedAreaMu": 7.2, "opinion": "卫星边界与轨迹显示实际7.2亩，农户少报1.2亩",
    "satelliteBoundaryRef": "SAT-BOUNDARY-B-2",
    "boundaryCoords": square_poly(113.125, 28.262, 7.2)})
check("村干部裁决少报", st == 200 and decB["status"] == "CONFIRMED_UNDERREPORT", f"http={st}")
check("少报补计费用与油补", decB["feeAfter"] > decB["feeBefore"]
      and decB["subsidyAfter"] > decB["subsidyBefore"])

st, me = call("GET", "/api/farmer/me", "farmer02")
check("诚信风险已记录", me["integrityRiskFlag"] is True and me["integrityRiskCount"] == 1, str(me))

st, blocked = call("POST", "/api/farmer/orders", "farmer02", {
    "cropType": "晚稻", "plotName": "新地块", "village": "双河村",
    "longitude": 113.13, "latitude": 28.26, "bookedAreaMu": 5,
    "operationType": "HARVESTING", "mudLevel": "LIGHT",
    "expectedStart": "2026-09-20 09:00:00", "expectedEnd": "2026-09-20 11:00:00",
    "strawRequested": False})
check("风险农户无边界预确认的新预约被拦截(400)", st == 400, f"http={st}")

st, me2 = call("GET", "/api/farmer/me", "farmer02")
fid = me2["userId"]
st, pre = call("POST", f"/api/village/farmers/{fid}/boundary-pre-confirm", "village01",
               {"plotName": "新地块", "confirmRef": "BC-2026-0920-01"})
check("村干部边界预确认", st == 200 and pre["pendingBoundaryRef"] == "BC-2026-0920-01", f"http={st}")

st, neworder = call("POST", "/api/farmer/orders", "farmer02", {
    "cropType": "晚稻", "plotName": "新地块", "village": "双河村",
    "longitude": 113.13, "latitude": 28.26, "bookedAreaMu": 5,
    "operationType": "HARVESTING", "mudLevel": "LIGHT",
    "expectedStart": "2026-09-20 09:00:00", "expectedEnd": "2026-09-20 11:00:00",
    "strawRequested": False, "boundaryConfirmRef": "BC-2026-0920-01"})
check("持预确认凭据可预约且凭据落单",
      st == 200 and neworder["boundaryPreConfirmed"] is True, f"http={st}")

# ============================ C. 已申报后复核 → 驳回重报 ============================
print("===== C. 申报审核中发生复核：必须驳回后按新数据重报 =====")
# 再造一单已结算作业并入新申报
oidC, drvC = settle_order("farmer03", "双河村", 113.128, 28.264, 7.0, "MEDIUM", 17)
st, claimC = call("POST", "/api/coop/subsidy/draft?period=2026-09-c", "coop")
check("第二份草稿生成", st == 200, f"订单数{claimC['orderCount']}")
cid = claimC["id"]
old_total = claimC["totalSubsidy"]
st, _ = call("POST", f"/api/coop/subsidy/{cid}/submit", "coop")
check("提交审核", st == 200)

st, rvC = call("POST", f"/api/farmer/orders/{oidC}/area-reviews", "farmer03", {
    "farmerClaimAreaMu": 6.0, "reason": "地头水沟占了面积"})
st, decC = call("POST", f"/api/village/area-reviews/{rvC['id']}/decide", "village01", {
    "reviewedAreaMu": 6.0, "opinion": "核定6亩，超算1亩"})
check("审核中复核裁决超算", st == 200 and decC["status"] == "CONFIRMED_OVERCHARGE")

st, claimC2 = call("GET", f"/api/auditor/claims/{cid}", "auditor")
check("申报单标记待调整", claimC2["needsAdjustment"] is True, claimC2.get("adjustmentNote", ""))
check("复核附件提示驳回重报", "驳回重报" in claimC2["attachments"][0]["handlingNote"])
st, blocked_appr = call("POST", f"/api/auditor/claims/{cid}/review", "auditor",
                        {"approved": True, "comment": "尝试直接通过"})
check("系统拦截：未重报不得审核通过", st == 400, f"http={st}")
st, rej = call("POST", f"/api/auditor/claims/{cid}/review", "auditor",
               {"approved": False, "comment": "按复核附件驳回，请按6亩重报"})
check("驳回成功", st == 200 and rej["status"] == "REJECTED")
st, claimD = call("POST", "/api/coop/subsidy/draft?period=2026-09-d", "coop")
# 被驳回申报中的订单可重新进入新草稿，并按最新核定面积取数
check("合作社按新数据重新申报", st == 200 and claimD["totalSubsidy"] < old_total - 1,
      f"{old_total}→{claimD['totalSubsidy']}")
st, _ = call("POST", f"/api/coop/subsidy/{claimD['id']}/submit", "coop")
st, final = call("POST", f"/api/auditor/claims/{claimD['id']}/review", "auditor",
                 {"approved": True, "comment": "重报数据与复核附件一致，准予补贴"})
check("复审通过", st == 200 and final["status"] == "APPROVED")

print("\n地块面积争议复核全部场景验收通过")
