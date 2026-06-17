# statements service: read-only reporting over the ledger's internal
# transactions API. It has no database of its own - it fetches transactions
# from the ledger and aggregates them in memory.

import os
import requests
from flask import Flask, jsonify, request
from datetime import datetime

app = Flask(__name__)

LEDGER_URL = os.environ.get("LEDGER_URL", "http://localhost:8082")


@app.route("/healthz")
def healthz():
    return jsonify({"status": "ok"})


def fetch_transactions(account_id, date_from, date_to):
    # talk to the ledger internal feed
    params = {"account_id": account_id}
    if date_from:
        params["from"] = date_from
    if date_to:
        params["to"] = date_to
    url = LEDGER_URL + "/internal/transactions"
    r = requests.get(url, params=params, timeout=10)
    if r.status_code != 200:
        return []
    data = r.json()
    return data.get("transactions", [])


@app.route("/statements/<account_id>")
def statements(account_id):
    date_from = request.args.get("from")
    date_to = request.args.get("to")
    type_filter = request.args.get("type")

    txns = fetch_transactions(account_id, date_from, date_to)

    out_list = []
    for t in txns:
        # filter by type if asked
        if type_filter and t.get("type") != type_filter:
            continue
        # build the dict by hand
        row = {}
        row["id"] = t.get("id")
        row["type"] = t.get("type")
        row["amount"] = t.get("amount")
        row["counterparty"] = t.get("counterparty")
        row["description"] = t.get("description")
        row["date"] = t.get("created_at")
        out_list.append(row)

    result = {}
    result["account_id"] = account_id
    result["from"] = date_from
    result["to"] = date_to
    result["transactions"] = out_list
    result["count"] = len(out_list)
    return jsonify(result)


@app.route("/statements/<account_id>/summary")
def summary(account_id):
    months = request.args.get("months")
    if months:
        try:
            months_n = int(months)
        except:
            months_n = 6
    else:
        months_n = 6

    txns = fetch_transactions(account_id, None, None)

    # bucket by month string YYYY-MM
    buckets = {}
    for t in txns:
        created = t.get("created_at")
        if not created:
            continue
        try:
            dt = datetime.fromisoformat(created.replace("Z", "+00:00"))
        except:
            continue
        key = "%04d-%02d" % (dt.year, dt.month)
        if key not in buckets:
            buckets[key] = {"total_in": 0.0, "total_out": 0.0}
        amt = t.get("amount") or 0
        ttype = t.get("type")
        if ttype == "deposit" or ttype == "payment_in":
            buckets[key]["total_in"] = buckets[key]["total_in"] + amt
        elif ttype == "payment_out":
            buckets[key]["total_out"] = buckets[key]["total_out"] + amt

    # sort the month keys descending and take the most recent N
    keys = list(buckets.keys())
    keys.sort()
    keys.reverse()
    keys = keys[:months_n]

    months_list = []
    for k in keys:
        b = buckets[k]
        tin = round(b["total_in"], 2)
        tout = round(b["total_out"], 2)
        m = {}
        m["month"] = k
        m["total_in"] = tin
        m["total_out"] = tout
        m["net"] = round(tin - tout, 2)
        months_list.append(m)

    res = {}
    res["account_id"] = account_id
    res["months"] = months_list
    return jsonify(res)


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8083)
