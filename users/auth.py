"""Shared token helpers for the users service.

The users service mints signed tokens on login; backend services validate them
with the same TOKEN_SECRET. We keep a copy of these helpers per service so that
each service stays a self-contained deployable unit (database-per-service spirit:
no shared internal package to import across service boundaries).

Tokens are JWTs (HS256) signed with the shared TOKEN_SECRET. The Java ledger
service validates the same tokens. The logical claims (user_id, full_name) are
unchanged from the previous itsdangerous-based token.
"""
import os

import jwt
from flask import jsonify, request

TOKEN_SECRET = os.environ.get("TOKEN_SECRET", "dev-secret-change-me")
ALGORITHM = "HS256"


def make_token(user_id, full_name):
    payload = {"user_id": user_id, "full_name": full_name}
    return jwt.encode(payload, TOKEN_SECRET, algorithm=ALGORITHM)


def verify_token(token):
    """Return the token payload dict, or None if the token is invalid/expired."""
    try:
        return jwt.decode(token, TOKEN_SECRET, algorithms=[ALGORITHM])
    except jwt.InvalidTokenError:
        return None


def current_identity():
    """Extract and verify the bearer token from the request, or None."""
    header = request.headers.get("Authorization", "")
    if not header.startswith("Bearer "):
        return None
    return verify_token(header[len("Bearer "):])


def require_auth(view):
    """Decorator: 401 unless a valid bearer token is present."""
    from functools import wraps

    @wraps(view)
    def wrapper(*args, **kwargs):
        identity = current_identity()
        if identity is None:
            return jsonify({"error": "unauthorized"}), 401
        request.identity = identity
        return view(*args, **kwargs)

    return wrapper
