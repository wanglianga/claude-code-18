#!/usr/bin/env python3
"""
合作社归属隔离接口测试（仅依赖 Python 标准库）。

业务规则：
1. 调度池 GET /api/coop/dispatch/pool 仅合作社可访问，其他角色 403；
2. 未分派(SUBMITTED)订单进全县共享调度池，所有合作社可见；
3. 订单一旦派给某合作社(DISPATCHED)，仅该合作社能在池中看到，
   其他合作社（coop2）看不到合作社1的订单。

前置：全新启动（演示数据含两张 SUBMITTED 预约单 id=1,2）。
用法：BASE=http://host.docker.internal:3018 python3 tests/api_pool_isolation_test.py
"""
import base64
import json
import os
import sys
import urllib.error
import urllib.request

BASE = os.environ.get("BASE", "http://host.docker.internal:3018")
PWD = "123456"


def call(method, path, user=None, body=None):
    req = urllib.request.Request(
        BASE + path,
        data=json.dumps(body).encode() if body is not None else None,
        method=method,
    )
    req.add_header("Content-Type", "application/json")
    if user:
        req.add_header(
            "Authorization",
            "Basic " + base64.b64encode(f"{user}:{PWD}".encode()).decode(),
        )
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


results = []


def check(name, cond, detail=""):
    results.append((name, cond, detail))
    print(f"  [{'PASS' if cond else 'FAIL'}] {name} {detail}")
    if not cond:
        sys.exit(1)


def pool(user):
    st, data = call("GET", "/api/coop/dispatch/pool", user)
    ids = {o["id"]: o["status"] for o in data} if st == 200 else {}
    return st, ids


print("== 1. 非合作社角色访问调度池必须 403 ==")
for role in ["farmer01", "driver01", "village01", "auditor"]:
    st, _ = call("GET", "/api/coop/dispatch/pool", role)
    check(f"{role} → 403", st == 403, f"http={st}")

print("== 2. 初始共享池：coop / coop2 都能看到两张未分派演示单 ==")
st, pool_coop = pool("coop")
check("coop 池 200", st == 200)
check("coop 看到共享未分派单 1、2", {1, 2} <= set(pool_coop), str(pool_coop))
st, pool_coop2 = pool("coop2")
check("coop2 池 200", st == 200)
check("coop2 看到共享未分派单 1、2", {1, 2} <= set(pool_coop2), str(pool_coop2))

print("== 3. coop(合作社1) 派机订单 1 ==")
st, sugg = call("POST", "/api/coop/orders/1/suggestions", "coop")
check("生成建议", st == 200 and len(sugg) >= 1, f"http={st}")
st, order = call(
    "POST",
    "/api/coop/orders/1/dispatch",
    "coop",
    {"suggestionId": sugg[0]["id"], "opinion": "归属隔离测试派机"},
)
check("合作社1派机成功", st == 200 and order["status"] == "DISPATCHED", f"http={st}")

print("== 4. coop 调度池仍含本社 DISPATCHTED 订单 1 ==")
st, pool_coop = pool("coop")
check("coop 看到订单1且状态 DISPATCHED",
      pool_coop.get(1) == "DISPATCHED", str(pool_coop))

print("== 5. coop2 调度池不得包含合作社1的订单 1 ==")
st, pool_coop2 = pool("coop2")
check("coop2 池 200", st == 200)
check("coop2 看不到订单 1", 1 not in pool_coop2, str(pool_coop2))
check("coop2 仍可见未分派共享单 2", pool_coop2.get(2) == "SUBMITTED", str(pool_coop2))

print("== 6. coop2 不可触达他社已派订单（建议/派机均拒绝） ==")
st, resp = call("POST", "/api/coop/orders/1/suggestions", "coop2")
check("coop2 对已派单生成建议被拒(4xx)", st in (400, 403), f"http={st}")
st, resp = call("POST", "/api/coop/orders/1/dispatch", "coop2",
                {"suggestionId": sugg[0]["id"]})
check("coop2 重复派机被拒(4xx)", st in (400, 403), f"http={st}")

print("\n合作社归属隔离测试全部通过：非合作社 403，coop2 池中不含合作社1订单")
