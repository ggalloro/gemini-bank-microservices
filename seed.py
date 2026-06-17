"""Seed Gemini Bank with synthetic data via the services' HTTP APIs.

Creates ~5 users (password demo1234), 1-2 accounts each with initial deposits,
and ~60 days of plausible transactions (salaries, internal transfers between
users, external payments to named merchants). It NEVER touches the databases
directly — everything goes through the public/internal HTTP endpoints, so the
seeded data exercises the same validation paths a real client would.

Run after the services are up:

    python seed.py

Honors USERS_URL / LEDGER_URL env vars (defaults to localhost).
"""
import os
import random
import time
from datetime import datetime, timedelta, timezone

import requests

USERS_URL = os.environ.get("USERS_URL", "http://localhost:8081")
LEDGER_URL = os.environ.get("LEDGER_URL", "http://localhost:8082")
PASSWORD = "demo1234"

USERS = [
    ("ana", "Ana Ferreira", "ana@example.com"),
    ("bruno", "Bruno Costa", "bruno@example.com"),
    ("carla", "Carla Nunes", "carla@example.com"),
    ("david", "David Klein", "david@example.com"),
    ("elena", "Elena Rossi", "elena@example.com"),
]

MERCHANTS = [
    ("DE89370400440532013000", "SuperMart"),
    ("DE12500105170648489890", "PowerCo Energy"),
    ("DE44500105175407324931", "StreamFlix"),
    ("DE75512108001245126199", "City Transit"),
    ("DE21500105172983217628", "Cafe Aurora"),
]

# A few external payments with socially-engineered descriptions, seeded into one
# customer's history as payments that went through before fraud protection
# existed. They give the dataset realistic scam-language examples (gift-card APP
# fraud, crypto scam, impersonation) across the fraud taxonomy.
SUSPICIOUS_PAYMENTS = [
    ("DE91100000000000000001", "GiftCard Rewards Ltd", 180.00,
     "URGENT: buy gift cards for the office, do not tell anyone"),
    ("DE91100000000000000002", "CryptoMax Invest", 240.00,
     "Guaranteed 300% return - act now before the window closes"),
    ("DE91100000000000000003", "Account Security Team", 95.00,
     "Bank fraud dept: verify your account and pay the release fee"),
]


def wait_for_services(timeout=60):
    deadline = time.time() + timeout
    for name, url in (("users", USERS_URL), ("ledger", LEDGER_URL)):
        while True:
            try:
                if requests.get(f"{url}/healthz", timeout=3).status_code == 200:
                    print(f"  {name} is up")
                    break
            except requests.RequestException:
                pass
            if time.time() > deadline:
                raise SystemExit(f"Timed out waiting for {name} at {url}")
            time.sleep(1)


def register_and_login(username, full_name, email):
    requests.post(
        f"{USERS_URL}/users",
        json={
            "username": username,
            "full_name": full_name,
            "email": email,
            "password": PASSWORD,
        },
        timeout=10,
    )  # 409 on re-run is fine
    resp = requests.post(
        f"{USERS_URL}/sessions",
        json={"username": username, "password": PASSWORD},
        timeout=10,
    )
    resp.raise_for_status()
    data = resp.json()
    return data["user_id"], data["token"]


def open_account(token, name):
    resp = requests.post(
        f"{LEDGER_URL}/accounts",
        json={"name": name},
        headers={"Authorization": f"Bearer {token}"},
        timeout=10,
    )
    resp.raise_for_status()
    return resp.json()


def deposit(token, account_id, amount, description):
    requests.post(
        f"{LEDGER_URL}/accounts/{account_id}/deposits",
        json={"amount": f"{amount:.2f}", "description": description},
        headers={"Authorization": f"Bearer {token}"},
        timeout=10,
    ).raise_for_status()


def pay_external(token, account_id, amount, iban, name, description):
    return requests.post(
        f"{LEDGER_URL}/accounts/{account_id}/payments",
        json={
            "amount": f"{amount:.2f}",
            "description": description,
            "beneficiary": {"type": "external", "iban": iban, "name": name},
        },
        headers={"Authorization": f"Bearer {token}"},
        timeout=10,
    )


def pay_internal(token, account_id, dest_account_id, amount, description):
    return requests.post(
        f"{LEDGER_URL}/accounts/{account_id}/payments",
        json={
            "amount": f"{amount:.2f}",
            "description": description,
            "beneficiary": {"type": "internal", "account_id": dest_account_id},
        },
        headers={"Authorization": f"Bearer {token}"},
        timeout=10,
    )


def main():
    random.seed(42)
    print("Waiting for services...")
    wait_for_services()

    print("Creating users and accounts...")
    people = []  # list of dicts: username, user_id, token, accounts[]
    for username, full_name, email in USERS:
        user_id, token = register_and_login(username, full_name, email)
        accounts = [open_account(token, "Checking")]
        if random.random() < 0.5:
            accounts.append(open_account(token, "Savings"))
        # Initial salary-style deposit so payments have funds.
        for acc in accounts:
            deposit(token, acc["id"], round(random.uniform(2000, 4000), 2),
                    "Opening balance")
        people.append(
            {
                "username": username,
                "full_name": full_name,
                "user_id": user_id,
                "token": token,
                "accounts": accounts,
            }
        )
        print(f"  {username}: {len(accounts)} account(s)")

    all_accounts = [(p, a) for p in people for a in p["accounts"]]

    print("Generating ~60 days of transactions...")
    today = datetime.now(timezone.utc).date()
    deposits = transfers = externals = 0
    for day_offset in range(60, 0, -1):
        day = today - timedelta(days=day_offset)
        # Monthly salary deposit around the 1st.
        if day.day == 1:
            for p in people:
                acc = p["accounts"][0]
                deposit(p["token"], acc["id"], round(random.uniform(2500, 3500), 2),
                        f"Salary {day.isoformat()}")
                deposits += 1
        # A few random external payments per day.
        for _ in range(random.randint(0, 3)):
            p, acc = random.choice(all_accounts)
            iban, mname = random.choice(MERCHANTS)
            amount = round(random.uniform(5, 120), 2)
            r = pay_external(p["token"], acc["id"], amount, iban, mname,
                             f"{mname} purchase")
            if r.status_code == 201:
                externals += 1
        # An occasional internal transfer between two different people.
        if random.random() < 0.5:
            (p1, a1), (p2, a2) = random.sample(all_accounts, 2)
            amount = round(random.uniform(20, 300), 2)
            r = pay_internal(p1["token"], a1["id"], a2["id"], amount,
                             f"Transfer to {p2['full_name']}")
            if r.status_code == 201:
                transfers += 1

    # Seed the socially-engineered payments into the first customer's history.
    suspicious = 0
    victim = people[0]
    vacc = victim["accounts"][0]
    for iban, mname, amount, memo in SUSPICIOUS_PAYMENTS:
        r = pay_external(victim["token"], vacc["id"], amount, iban, mname, memo)
        if r.status_code == 201:
            suspicious += 1

    print(
        f"Done. salary deposits={deposits}, internal transfers={transfers}, "
        f"external payments={externals}, socially-engineered examples={suspicious}"
    )
    print("\nLogin with any of: " + ", ".join(u[0] for u in USERS))
    print(f"Password for all: {PASSWORD}")


if __name__ == "__main__":
    main()
