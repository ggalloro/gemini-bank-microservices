import os
import tempfile

import pytest

# Use an isolated temp DB before importing the app/models.
_tmp = tempfile.NamedTemporaryFile(suffix=".db", delete=False)
_tmp.close()
os.environ["USERS_DB"] = _tmp.name
os.environ["TOKEN_SECRET"] = "test-secret"

import app as app_module  # noqa: E402
import models  # noqa: E402


@pytest.fixture
def client():
    # Fresh schema for each test.
    if os.path.exists(_tmp.name):
        os.remove(_tmp.name)
    models.init_db()
    flask_app = app_module.create_app()
    flask_app.config.update(TESTING=True)
    with flask_app.test_client() as c:
        yield c
