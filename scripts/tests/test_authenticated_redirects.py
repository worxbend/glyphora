"""Offline urllib redirect-chain tests; Authorization is fixture data only."""
import importlib.util
import io
from email.message import Message
import os
from pathlib import Path
import unittest
from unittest.mock import patch
import urllib.error
import urllib.request
from urllib.response import addinfourl

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("consumer_redirects", ROOT / "scripts/verify-packaged-consumer.py")
assert SPEC is not None and SPEC.loader is not None
consumer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(consumer)
ORIGIN = "https://maven.pkg.github.com"
FIXTURE_AUTH = "Basic explicit-non-secret-test-fixture"


class Transport(urllib.request.HTTPSHandler, urllib.request.HTTPHandler):
    def __init__(self, locations, status=302):
        super().__init__()
        self.locations = iter(locations)
        self.delivered = []
        self.status = status

    def https_open(self, request):
        self.delivered.append((request.full_url, request.get_header("Authorization")))
        location = next(self.locations, None)
        headers = Message()
        if location:
            headers["Location"] = location
        response = addinfourl(io.BytesIO(b"fixture"), headers, request.full_url, self.status if location else 200)
        response.msg = "fixture"
        return response

    http_open = https_open


class AuthenticatedRedirectTests(unittest.TestCase):
    def fetch(self, locations, status=302):
        transport = Transport(locations, status)
        # Use the production redirect handler, real OpenerDirector and urllib's
        # HTTPErrorProcessor; replace only the final network transport.
        policy = next(handler for handler in consumer.authenticated_opener(ORIGIN).handlers
                      if isinstance(handler, urllib.request.HTTPRedirectHandler))
        opener = urllib.request.build_opener(policy, transport)
        self.transport = transport
        return opener.open(urllib.request.Request(ORIGIN + "/start", headers={"Authorization": FIXTURE_AUTH}))

    def test_same_origin_relative_and_explicit_default_port_chain(self):
        with self.fetch(["/second", ORIGIN + ":443/third"]):
            pass
        self.assertEqual(len(self.transport.delivered), 3)
        self.assertTrue(all(auth == FIXTURE_AUTH for _, auth in self.transport.delivered))

    def test_disallowed_destinations_are_rejected_before_delivery(self):
        for target in ("https://example.com/leak", "http://maven.pkg.github.com/leak",
                       ORIGIN + ":444/leak", "https://maven.pkg.github.com.evil.test/leak",
                       "https://fixture-user@maven.pkg.github.com/leak"):
            with self.subTest(target=target), self.assertRaisesRegex(ValueError, "authenticated redirect"):
                self.fetch([target])
            self.assertEqual(self.transport.delivered, [(ORIGIN + "/start", FIXTURE_AUTH)])

    def test_later_cross_origin_hop_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "authenticated redirect"):
            self.fetch(["/second", "https://example.com/leak", ORIGIN + "/back"])
        self.assertEqual(self.transport.delivered,
                         [(ORIGIN + "/start", FIXTURE_AUTH), (ORIGIN + "/second", FIXTURE_AUTH)])

    def test_redirect_loop_remains_bounded(self):
        with self.assertRaises(urllib.error.HTTPError):
            self.fetch(["/start"] * 20)
        self.assertLess(len(self.transport.delivered), 20)

    def test_every_supported_redirect_status_uses_same_policy(self):
        for status in (301, 302, 303, 307, 308):
            with self.subTest(status=status):
                with self.fetch(["/second"], status):
                    pass
                self.assertEqual(len(self.transport.delivered), 2)
                with self.assertRaisesRegex(ValueError, "authenticated redirect"):
                    self.fetch(["https://example.com/leak"], status)
                self.assertEqual(len(self.transport.delivered), 1)

    def test_remote_verifier_really_installs_policy_before_downloading(self):
        transport = Transport(["/second", "https://example.com/leak"])
        build_opener = urllib.request.build_opener
        def fixture_opener(*handlers):
            return build_opener(*handlers, transport)
        with patch.dict(os.environ, {"GITHUB_PACKAGES_USERNAME": "fixture-user",
                                     "GITHUB_PACKAGES_PASSWORD": "fixture-password"}, clear=True), \
                patch.object(urllib.request, "build_opener", side_effect=fixture_opener), \
                patch.object(urllib.request, "urlopen", side_effect=AssertionError("authenticated urlopen bypass")):
            with self.assertRaisesRegex(ValueError, "authenticated redirect"):
                consumer.verify_remote_artifacts(Path("unused-fixture-repository"), ORIGIN + "/fixture", "0.0.0")
        self.assertEqual(len(transport.delivered), 2)
        self.assertEqual([url.split("/", 3)[2] for url, _ in transport.delivered], ["maven.pkg.github.com"] * 2)
        self.assertTrue(all(auth and auth.startswith("Basic ") for _, auth in transport.delivered))


if __name__ == "__main__":
    unittest.main()
