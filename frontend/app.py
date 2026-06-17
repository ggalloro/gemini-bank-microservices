"""frontend service — server-rendered UI.

No database, no business logic. It orchestrates calls to the backend services
and renders Jinja2 templates. The login token is kept in the Flask session
cookie and forwarded to backend services as `Authorization: Bearer <token>`.
"""
import os

import requests
from flask import (
    Flask,
    flash,
    redirect,
    render_template,
    request,
    session,
    url_for,
)

USERS_URL = os.environ.get("USERS_URL", "http://localhost:8081")
LEDGER_URL = os.environ.get("LEDGER_URL", "http://localhost:8082")
STATEMENTS_URL = os.environ.get("STATEMENTS_URL", "http://localhost:8083")
TIMEOUT = 10


def create_app():
    app = Flask(__name__)
    app.secret_key = os.environ.get("FRONTEND_SECRET", "dev-frontend-secret")

    def auth_headers():
        token = session.get("token")
        return {"Authorization": f"Bearer {token}"} if token else {}

    def logged_in():
        return "token" in session

    @app.context_processor
    def inject_user():
        return {"full_name": session.get("full_name"), "logged_in": logged_in()}

    @app.get("/healthz")
    def healthz():
        return {"status": "ok"}

    @app.get("/")
    def index():
        if logged_in():
            return redirect(url_for("dashboard"))
        return redirect(url_for("login"))

    # ---- auth ----

    @app.route("/register", methods=["GET", "POST"])
    def register():
        if request.method == "POST":
            payload = {
                "username": request.form.get("username", "").strip(),
                "full_name": request.form.get("full_name", "").strip(),
                "email": request.form.get("email", "").strip(),
                "password": request.form.get("password", ""),
            }
            resp = requests.post(f"{USERS_URL}/users", json=payload, timeout=TIMEOUT)
            if resp.status_code == 201:
                flash("Account created. Please log in.", "success")
                return redirect(url_for("login"))
            if resp.status_code == 409:
                flash("That username or email is already taken.", "danger")
            else:
                flash("Could not register. Check your details.", "danger")
        return render_template("register.html")

    @app.route("/login", methods=["GET", "POST"])
    def login():
        if request.method == "POST":
            payload = {
                "username": request.form.get("username", "").strip(),
                "password": request.form.get("password", ""),
            }
            resp = requests.post(f"{USERS_URL}/sessions", json=payload, timeout=TIMEOUT)
            if resp.status_code == 200:
                data = resp.json()
                session["token"] = data["token"]
                session["user_id"] = data["user_id"]
                session["full_name"] = data["full_name"]
                return redirect(url_for("dashboard"))
            flash("Invalid username or password.", "danger")
        return render_template("login.html")

    @app.get("/logout")
    def logout():
        session.clear()
        return redirect(url_for("login"))

    # ---- dashboard & accounts ----

    @app.get("/dashboard")
    def dashboard():
        if not logged_in():
            return redirect(url_for("login"))
        resp = requests.get(
            f"{LEDGER_URL}/accounts",
            params={"user_id": session["user_id"]},
            headers=auth_headers(),
            timeout=TIMEOUT,
        )
        accounts = resp.json() if resp.status_code == 200 else []
        return render_template("dashboard.html", accounts=accounts)

    @app.get("/account/<int:account_id>")
    def account_detail(account_id):
        if not logged_in():
            return redirect(url_for("login"))
        acc = requests.get(
            f"{LEDGER_URL}/accounts/{account_id}",
            headers=auth_headers(),
            timeout=TIMEOUT,
        )
        if acc.status_code != 200:
            flash("Account not found.", "danger")
            return redirect(url_for("dashboard"))
        account = acc.json()
        stmt = requests.get(
            f"{STATEMENTS_URL}/statements/{account_id}", timeout=TIMEOUT
        )
        txns = stmt.json().get("transactions", []) if stmt.status_code == 200 else []
        # Most recent first, last 10.
        txns = list(reversed(txns))[:10]
        return render_template(
            "account.html", account=account, transactions=txns
        )

    @app.route("/account/<int:account_id>/deposit", methods=["GET", "POST"])
    def deposit(account_id):
        if not logged_in():
            return redirect(url_for("login"))
        if request.method == "POST":
            payload = {
                "amount": request.form.get("amount", "").strip(),
                "description": request.form.get("description", "").strip(),
            }
            resp = requests.post(
                f"{LEDGER_URL}/accounts/{account_id}/deposits",
                json=payload,
                headers=auth_headers(),
                timeout=TIMEOUT,
            )
            if resp.status_code == 201:
                flash("Deposit successful.", "success")
                return redirect(url_for("account_detail", account_id=account_id))
            flash("Invalid amount.", "danger")
        return render_template("deposit.html", account_id=account_id)

    @app.route("/account/<int:account_id>/payment", methods=["GET", "POST"])
    def payment(account_id):
        if not logged_in():
            return redirect(url_for("login"))

        # Build the internal-beneficiary dropdown: other accounts + owner names.
        accounts_resp = _all_internal_accounts(auth_headers())
        internal_accounts = [a for a in accounts_resp if a["id"] != account_id]

        if request.method == "POST":
            btype = request.form.get("beneficiary_type")
            amount = request.form.get("amount", "").strip()
            description = request.form.get("description", "").strip()
            if btype == "internal":
                beneficiary = {
                    "type": "internal",
                    "account_id": request.form.get("internal_account_id", type=int),
                }
            else:
                beneficiary = {
                    "type": "external",
                    "iban": request.form.get("iban", "").strip(),
                    "name": request.form.get("beneficiary_name", "").strip(),
                }
            resp = requests.post(
                f"{LEDGER_URL}/accounts/{account_id}/payments",
                json={
                    "amount": amount,
                    "description": description,
                    "beneficiary": beneficiary,
                },
                headers=auth_headers(),
                timeout=TIMEOUT,
            )
            if resp.status_code == 201:
                flash("Payment sent.", "success")
                return redirect(url_for("account_detail", account_id=account_id))
            if resp.status_code == 422:
                flash("Payment declined: insufficient funds.", "danger")
            elif resp.status_code == 400:
                flash("Invalid payment details.", "danger")
            else:
                flash("Payment could not be processed.", "danger")

        return render_template(
            "payment.html",
            account_id=account_id,
            internal_accounts=internal_accounts,
        )

    @app.get("/account/<int:account_id>/statement")
    def statement(account_id):
        if not logged_in():
            return redirect(url_for("login"))
        date_from = request.args.get("from", "")
        date_to = request.args.get("to", "")
        type_filter = request.args.get("type", "")

        params = {}
        if date_from:
            params["from"] = date_from
        if date_to:
            params["to"] = date_to
        if type_filter:
            params["type"] = type_filter

        stmt = requests.get(
            f"{STATEMENTS_URL}/statements/{account_id}",
            params=params,
            timeout=TIMEOUT,
        )
        data = stmt.json() if stmt.status_code == 200 else {"transactions": [], "count": 0}

        summary = requests.get(
            f"{STATEMENTS_URL}/statements/{account_id}/summary",
            params={"months": 6},
            timeout=TIMEOUT,
        )
        months = summary.json().get("months", []) if summary.status_code == 200 else []

        return render_template(
            "statement.html",
            account_id=account_id,
            data=data,
            months=months,
            filters={"from": date_from, "to": date_to, "type": type_filter},
        )

    def _all_internal_accounts(headers):
        """Accounts of all users (id, name, owner full_name) for the dropdown."""
        users_resp = requests.get(f"{USERS_URL}/users", headers=headers, timeout=TIMEOUT)
        if users_resp.status_code != 200:
            return []
        result = []
        for u in users_resp.json():
            accs = requests.get(
                f"{LEDGER_URL}/accounts",
                params={"user_id": u["id"]},
                headers=headers,
                timeout=TIMEOUT,
            )
            if accs.status_code != 200:
                continue
            for a in accs.json():
                result.append(
                    {"id": a["id"], "name": a["name"], "owner": u["full_name"]}
                )
        return result

    return app


app = create_app()

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8080)
