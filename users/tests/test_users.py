def register(client, username="ana", email="ana@example.com"):
    return client.post(
        "/users",
        json={
            "username": username,
            "full_name": "Ana Example",
            "email": email,
            "password": "demo1234",
        },
    )


def login(client, username="ana", password="demo1234"):
    return client.post("/sessions", json={"username": username, "password": password})


def test_healthz(client):
    resp = client.get("/healthz")
    assert resp.status_code == 200
    assert resp.get_json() == {"status": "ok"}


def test_register_happy_path(client):
    resp = register(client)
    assert resp.status_code == 201
    body = resp.get_json()
    assert body["username"] == "ana"
    assert body["full_name"] == "Ana Example"
    assert "id" in body


def test_register_missing_fields(client):
    resp = client.post("/users", json={"username": "x"})
    assert resp.status_code == 400
    assert resp.get_json()["error"] == "missing_fields"


def test_register_duplicate_conflicts(client):
    register(client)
    resp = register(client)
    assert resp.status_code == 409
    assert resp.get_json()["error"] == "user_exists"


def test_login_happy_path(client):
    register(client)
    resp = login(client)
    assert resp.status_code == 200
    body = resp.get_json()
    assert "token" in body
    assert body["full_name"] == "Ana Example"
    assert "user_id" in body


def test_login_returns_jwt_with_expected_claims(client):
    import jwt

    user_id = register(client).get_json()["id"]
    token = login(client).get_json()["token"]
    # A JWT is three base64url segments separated by dots.
    assert token.count(".") == 2
    claims = jwt.decode(token, "test-secret", algorithms=["HS256"])
    assert claims == {"user_id": user_id, "full_name": "Ana Example"}


def test_login_wrong_password(client):
    register(client)
    resp = login(client, password="nope")
    assert resp.status_code == 401
    assert resp.get_json()["error"] == "invalid_credentials"


def test_login_unknown_user(client):
    resp = login(client, username="ghost")
    assert resp.status_code == 401


def test_get_user_requires_auth(client):
    resp = register(client)
    user_id = resp.get_json()["id"]
    # No Authorization header.
    resp = client.get(f"/users/{user_id}")
    assert resp.status_code == 401


def test_get_user_with_token(client):
    user_id = register(client).get_json()["id"]
    token = login(client).get_json()["token"]
    resp = client.get(
        f"/users/{user_id}", headers={"Authorization": f"Bearer {token}"}
    )
    assert resp.status_code == 200
    body = resp.get_json()
    assert body == {"id": user_id, "username": "ana", "full_name": "Ana Example"}


def test_get_user_invalid_token(client):
    user_id = register(client).get_json()["id"]
    resp = client.get(
        f"/users/{user_id}", headers={"Authorization": "Bearer garbage"}
    )
    assert resp.status_code == 401


def test_list_users(client):
    register(client, username="ana", email="ana@example.com")
    register(client, username="bruno", email="bruno@example.com")
    token = login(client).get_json()["token"]
    resp = client.get("/users", headers={"Authorization": f"Bearer {token}"})
    assert resp.status_code == 200
    users = resp.get_json()
    assert len(users) == 2
    assert set(users[0].keys()) == {"id", "full_name"}
