"""Offline urllib redirect-chain tests; Authorization is fixture data only."""
import importlib.util
import io
from email.message import Message
from pathlib import Path
import unittest
import urllib.error
import urllib.request
from urllib.response import addinfourl

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("consumer_redirects", ROOT / "scripts/verify-packaged-consumer.py")
consumer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(consumer)
ORIGIN = "https://maven.pkg.github.com"
FIXTURE_AUTH = "Basic explicit-non-secret-test-fixture"


class Transport(urllib.request.HTTPSHandler, urllib.request.HTTPHandler):
    def __init__(self, locations):
        super().__init__()
        self.locations = iter(locations)
        self.delivered = []

    def https_open(self, request):
        self.delivered.append((request.full_url, request.get_header("Authorization")))
        location = next(self.locations, None)
        headers = Message()
        if location:
            headers["Location"] = location
        response = addinfourl(io.BytesIO(b"fixture"), headers, request.full_url, 302 if location else 200)
        response.msg = "fixture"
        return response

    http_open = https_open


class AuthenticatedRedirectTests(unittest.TestCase):
    def fetch(self, locations):
        transport = Transport(locations)
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


if __name__ == "__main__":
    unittest.main()
