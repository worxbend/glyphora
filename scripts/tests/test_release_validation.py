"""Regression tests for the release gate; POM fixtures are explicit test data."""

import importlib.util
import io
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import urllib.error
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]


class ReleaseWorkflowTests(unittest.TestCase):
    def test_publish_requires_reusable_validation(self):
        publish = (ROOT / ".github/workflows/publish.yml").read_text()
        self.assertIn("uses: ./.github/workflows/ci.yml", publish)
        self.assertIn("needs: validate", publish)
        self.assertIn("needs.validate.outputs.validated-sha", publish)

    def test_ci_exposes_only_an_aggregate_validated_sha(self):
        ci = (ROOT / ".github/workflows/ci.yml").read_text()
        self.assertIn("workflow_call:", ci)
        self.assertIn("value: ${{ jobs.validated.outputs.sha }}", ci)
        self.assertIn("needs: [discipline, workflow-checks, discover, lint, build, test, example, native-image, website-build, packaged-consumer]", ci)
        self.assertIn("python3 scripts/verify-packaged-consumer.py", ci)


class PublishedPomTests(unittest.TestCase):
    def setUp(self):
        path = ROOT / "scripts/verify-packaged-consumer.py"
        self.assertTrue(path.is_file(), "packaged artifact validation is missing")
        spec = importlib.util.spec_from_file_location("packaged_consumer", path)
        assert spec is not None and spec.loader is not None
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.repository = Path(self.directory.name)
        for name, edges in self.module.ARTIFACT_EDGES.items():
            self.write_pom(name, edges)

    def write_pom(self, name, edges, extra=(), version="0.16.0"):
        project = ET.Element("project", xmlns="http://maven.apache.org/POM/4.0.0")
        for key, value in (("groupId", "com.worxbend"), ("artifactId", name), ("version", version)):
            ET.SubElement(project, key).text = value
        dependencies = ET.SubElement(project, "dependencies")
        for group, artifact, dependency_version, scope in [
            ("com.worxbend", edge, "0.16.0", "compile") for edge in edges
        ] + list(extra):
            dependency = ET.SubElement(dependencies, "dependency")
            for key, value in (("groupId", group), ("artifactId", artifact), ("version", dependency_version), ("scope", scope)):
                ET.SubElement(dependency, key).text = value
        destination = self.repository / "com/worxbend" / name / "0.16.0" / f"{name}-0.16.0.pom"
        destination.parent.mkdir(parents=True, exist_ok=True)
        ET.ElementTree(project).write(destination, encoding="utf-8", xml_declaration=True)

    def test_intended_dependency_graph_passes(self):
        self.module.validate_poms(self.repository, "0.16.0")

    def test_missing_production_edge_fails(self):
        self.write_pom("tui-runtime_3", {"tui-core_3"})
        with self.assertRaisesRegex(ValueError, "dependencies"):
            self.module.validate_poms(self.repository, "0.16.0")

    def test_extra_production_edge_fails(self):
        self.write_pom("tui-macros_3", {"tui-core_3"})
        with self.assertRaisesRegex(ValueError, "dependencies"):
            self.module.validate_poms(self.repository, "0.16.0")

    def test_scala_test_leak_fails(self):
        self.write_pom("tui-core_3", set(), [("org.scalatest", "scalatest_3", "3.2.20", "compile")])
        with self.assertRaisesRegex(ValueError, "test dependency"):
            self.module.validate_poms(self.repository, "0.16.0")

    def test_wrong_release_version_fails(self):
        self.write_pom("tui-core_3", set(), version="0.15.0")
        with self.assertRaisesRegex(ValueError, "coordinates"):
            self.module.validate_poms(self.repository, "0.16.0")

    def test_old_group_id_fails(self):
        pom = self.repository / "com/worxbend/tui-core_3/0.16.0/tui-core_3-0.16.0.pom"
        tree = ET.parse(pom)
        group = tree.getroot().find("m:groupId", self.module.NS)
        assert group is not None
        group.text = "io.worxbend"
        tree.write(pom, encoding="utf-8", xml_declaration=True)
        with self.assertRaisesRegex(ValueError, "coordinates"):
            self.module.validate_poms(self.repository, "0.16.0")

    def test_test_scoped_library_edge_fails(self):
        self.write_pom("tui-widgets_3", set(), [("com.worxbend", "tui-core_3", "0.16.0", "test")])
        with self.assertRaisesRegex(ValueError, "scope"):
            self.module.validate_poms(self.repository, "0.16.0")

    def add_dependency_element(self, tag, value):
        pom = self.repository / "com/worxbend/tui-runtime_3/0.16.0/tui-runtime_3-0.16.0.pom"
        tree = ET.parse(pom)
        dependency = tree.getroot().find("m:dependencies/m:dependency", self.module.NS)
        assert dependency is not None
        element = ET.SubElement(dependency, f"{{{self.module.NS['m']}}}{tag}")
        element.text = value
        tree.write(pom, encoding="utf-8", xml_declaration=True)
        return pom

    def test_optional_production_dependency_fails(self):
        self.add_dependency_element("optional", "true")
        with self.assertRaisesRegex(ValueError, "optional"):
            self.module.validate_poms(self.repository, "0.16.0")

    def test_duplicate_production_dependency_fails(self):
        self.write_pom("tui-runtime_3", {"tui-core_3", "tui-terminal_3"},
                       [("com.worxbend", "tui-core_3", "0.16.0", "compile")])
        with self.assertRaisesRegex(ValueError, "duplicate"):
            self.module.validate_poms(self.repository, "0.16.0")

    def test_wrong_production_dependency_version_fails(self):
        self.write_pom("tui-widgets_3", set(), [("com.worxbend", "tui-core_3", "0.15.0", "compile")])
        with self.assertRaisesRegex(ValueError, "version/scope"):
            self.module.validate_poms(self.repository, "0.16.0")

    def test_non_jar_dependency_fails(self):
        self.add_dependency_element("type", "pom")
        with self.assertRaisesRegex(ValueError, "jar"):
            self.module.validate_poms(self.repository, "0.16.0")

    def test_classified_dependency_fails(self):
        self.add_dependency_element("classifier", "tests")
        with self.assertRaisesRegex(ValueError, "classifier"):
            self.module.validate_poms(self.repository, "0.16.0")

    def test_transitive_exclusion_fails(self):
        pom = self.add_dependency_element("exclusions", None)
        tree = ET.parse(pom)
        exclusions = tree.getroot().find(".//m:exclusions", self.module.NS)
        assert exclusions is not None
        exclusion = ET.SubElement(exclusions, f"{{{self.module.NS['m']}}}exclusion")
        ET.SubElement(exclusion, f"{{{self.module.NS['m']}}}groupId").text = "com.worxbend"
        ET.SubElement(exclusion, f"{{{self.module.NS['m']}}}artifactId").text = "tui-core_3"
        tree.write(pom, encoding="utf-8", xml_declaration=True)
        with self.assertRaisesRegex(ValueError, "exclusion"):
            self.module.validate_poms(self.repository, "0.16.0")

    def test_missing_pom_fails(self):
        pom = self.repository / "com/worxbend/tui-core_3/0.16.0/tui-core_3-0.16.0.pom"
        pom.unlink()
        with self.assertRaises(FileNotFoundError):
            self.module.validate_poms(self.repository, "0.16.0")


class RemoteArtifactsTests(unittest.TestCase):
    def setUp(self):
        path = ROOT / "scripts/verify-packaged-consumer.py"
        spec = importlib.util.spec_from_file_location("remote_consumer", path)
        assert spec is not None and spec.loader is not None
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)
        # Keep these byte-comparison/retry tests at the transport seam; redirect
        # policy itself is exercised with a real opener in test_authenticated_redirects.
        opener = patch.object(self.module, "authenticated_opener")
        factory = opener.start()
        factory.return_value.open.side_effect = lambda *args, **kwargs: self.module.urllib.request.urlopen(*args, **kwargs)
        self.addCleanup(opener.stop)
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.repository = Path(self.directory.name)
        self.content = {}
        for artifact in self.module.ARTIFACT_EDGES:
            for suffix in (".pom", ".jar", "-sources.jar", "-javadoc.jar"):
                relative = f"com/worxbend/{artifact}/0.18.0/{artifact}-0.18.0{suffix}"
                data = f"explicit fixture: {relative}".encode()
                destination = self.repository / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(data)
                self.content[relative] = data
        credentials = patch.dict(os.environ, {"GITHUB_PACKAGES_USERNAME": "fixture-user",
                                             "GITHUB_PACKAGES_PASSWORD": "fixture-password"})
        credentials.start()
        self.addCleanup(credentials.stop)

    def download(self, request, timeout):
        self.assertEqual(timeout, 30)
        self.assertTrue(request.get_header("Authorization").startswith("Basic "))
        relative = request.full_url.removeprefix("https://maven.pkg.github.com/worxbend/glyphora/")
        return io.BytesIO(self.content[relative])

    def check(self, url="https://maven.pkg.github.com/worxbend/glyphora"):
        self.module.verify_remote_artifacts(self.repository, url, "0.18.0")

    def test_every_artifact_is_downloaded_and_compared(self):
        with patch.object(self.module.urllib.request, "urlopen", side_effect=self.download) as download:
            self.check()
        self.assertEqual(download.call_count, 28)

    def test_corrupt_remote_file_fails(self):
        with patch.object(self.module.urllib.request, "urlopen", return_value=io.BytesIO(b"wrong release")):
            with self.assertRaisesRegex(ValueError, "differs"):
                self.check()

    def test_credentials_are_not_sent_to_another_host(self):
        with patch.object(self.module.urllib.request, "urlopen") as download:
            with self.assertRaisesRegex(ValueError, "HTTPS GitHub Packages or Maven Central"):
                self.check("https://example.com/repository")
        download.assert_not_called()

    def test_central_downloads_every_file_without_authentication(self):
        def download(request, timeout):
            self.assertEqual(timeout, 30)
            self.assertIsNone(request.get_header("Authorization"))
            relative = request.full_url.removeprefix("https://repo.maven.apache.org/maven2/")
            return io.BytesIO(self.content[relative])

        with patch.object(self.module.urllib.request, "urlopen", side_effect=download) as fetch:
            self.check("https://repo.maven.apache.org/maven2")
        self.assertEqual(fetch.call_count, 28)

    def test_central_does_not_require_github_credentials(self):
        with patch.dict(os.environ, {}, clear=True):
            self.test_central_downloads_every_file_without_authentication()

    def test_central_waits_for_publication_propagation(self):
        responses = iter([urllib.error.HTTPError("fixture", 404, "not found", {}, None) for _ in range(7)])

        def download(request, timeout):
            response = next(responses, None)
            if response is not None:
                raise response
            self.assertIsNone(request.get_header("Authorization"))
            relative = request.full_url.removeprefix("https://repo.maven.apache.org/maven2/")
            return io.BytesIO(self.content[relative])

        with patch.object(self.module.urllib.request, "urlopen", side_effect=download) as fetch, \
                patch.object(self.module.time, "sleep") as sleep:
            self.check("https://repo.maven.apache.org/maven2")
        self.assertEqual(fetch.call_count, 35)
        self.assertEqual(sleep.call_count, 7)

    def test_authentication_failure_is_not_retried(self):
        error = urllib.error.HTTPError("fixture", 401, "unauthorized", {}, None)
        with patch.object(self.module.urllib.request, "urlopen", side_effect=error) as download:
            with self.assertRaises(urllib.error.HTTPError):
                self.check()
        self.assertEqual(download.call_count, 1)

    def test_new_package_visibility_is_retried(self):
        responses = iter([urllib.error.HTTPError("fixture", 404, "not found", {}, None)])

        def download(request, timeout):
            response = next(responses, None)
            if response is not None:
                raise response
            return self.download(request, timeout)

        with patch.object(self.module.urllib.request, "urlopen", side_effect=download) as fetch, \
                patch.object(self.module.time, "sleep") as sleep:
            self.check()
        self.assertEqual(fetch.call_count, 29)
        sleep.assert_called_once_with(5)


class PackagedClasspathTests(unittest.TestCase):
    def setUp(self):
        path = ROOT / "scripts/verify-packaged-consumer.py"
        spec = importlib.util.spec_from_file_location("packaged_consumer", path)
        assert spec is not None and spec.loader is not None
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.repository = Path(self.directory.name)
        self.current = self.repository / "com/worxbend/tui-core_3/0.16.0/tui-core_3-0.16.0.jar"
        self.current.parent.mkdir(parents=True)
        self.current.write_bytes(b"explicit current-version test fixture")

    def check(self, entries):
        self.module.verify_classpath([str(entry) for entry in entries], self.repository, "0.16.0", {"tui-core_3"})

    def test_single_staged_jar_passes(self):
        self.check([self.current])

    def test_mill_path_references_pass(self):
        for kind in ("ref", "qref"):
            with self.subTest(kind=kind):
                self.check([f"{kind}:v1:12345678:{self.current}"])

    def test_path_reference_cannot_hide_source_checkout(self):
        for kind in ("ref", "qref"):
            with self.subTest(kind=kind), self.assertRaisesRegex(ValueError, "source checkout"):
                self.check([f"{kind}:v1:12345678:{ROOT / 'out/core/compile.dest/classes'}", self.current])

    def test_stale_jar_is_rejected_in_either_order(self):
        stale = self.repository / "tui-core_3-0.15.0.jar"
        stale.write_bytes(b"explicit stale-version test fixture")
        for entries in ([self.current, stale], [stale, self.current]):
            with self.subTest(entries=entries), self.assertRaisesRegex(ValueError, "unexpected"):
                self.check(entries)

    def test_duplicate_artifact_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "duplicate"):
            self.check([self.current, self.current])

    def test_wrong_current_hash_is_rejected(self):
        other = self.repository / "other/tui-core_3-0.16.0.jar"
        other.parent.mkdir()
        other.write_bytes(b"explicit different current-version test fixture")
        with self.assertRaisesRegex(ValueError, "staged"):
            self.check([other])

    def test_source_checkout_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "source checkout"):
            self.check([ROOT / "out/core/compile.dest/classes", self.current])


if __name__ == "__main__":
    unittest.main()
