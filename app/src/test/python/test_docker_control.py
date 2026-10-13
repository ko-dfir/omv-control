import copy
import importlib.util
from pathlib import Path
import unittest
import urllib.parse
import sys

sys.dont_write_bytecode = True

path = Path(__file__).resolve().parents[2] / "main/assets/docker_control.py"
spec = importlib.util.spec_from_file_location("docker_control", path)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

INSPECT = {
    "Id": "a" * 64, "Name": "/web", "Image": "sha256:local-image",
    "Config": {"Image": "web:latest", "Env": ["SECRET=a=b"], "Labels": {}, "Volumes": {"/data": {}}},
    "HostConfig": {"NetworkMode": "app", "RestartPolicy": {"Name": "always", "MaximumRetryCount": 0},
                   "Memory": 1024, "CapAdd": ["NET_ADMIN"], "Binds": ["anonymous:/data:ro"], "Mounts": []},
    "Mounts": [{"Type": "volume", "Name": "anonymous", "Source": "/var/lib/docker/volumes/anonymous/_data", "Destination": "/data", "RW": False}],
    "State": {"Status": "running", "Running": True},
    "NetworkSettings": {"Networks": {"app": {"Aliases": ["web", "a" * 12, "custom"], "IPAddress": "172.18.0.2", "IPAMConfig": {"IPv4Address": "172.18.0.2"}}}}
}
DRAFT = {"environment": ["SECRET=a=b", "TZ=Europe/Bucharest"], "ports": [{"container": "80", "host": "8080", "address": "127.0.0.1", "protocol": "tcp"}],
         "mounts": [{"type": "volume", "source": "anonymous", "target": "/data", "readonly": True}], "restart": "unless-stopped", "retries": 0}


class FakeDocker:
    def __init__(self, fail_create=False, fail_start=False):
        self.containers = {INSPECT["Id"]: copy.deepcopy(INSPECT)}
        self.calls = []
        self.fail_create = fail_create
        self.fail_start = fail_start

    def request(self, method, path, payload=None):
        self.calls.append((method, path, copy.deepcopy(payload)))
        if path.startswith("/containers/create"):
            if self.fail_create:
                raise RuntimeError("Port already in use")
            name = urllib.parse.parse_qs(urllib.parse.urlsplit(path).query)["name"][0]
            self.containers["replacement"] = {"Id": "replacement", "Name": "/" + name, "State": {"Status": "created", "Running": False}, "HostConfig": payload["HostConfig"]}
            return {"Id": "replacement"}
        container_id = path.split("/")[2].split("?")[0]
        value = self.containers[container_id]
        if method == "GET":
            return copy.deepcopy(value)
        if method == "DELETE":
            del self.containers[container_id]
        elif "/stop" in path:
            value["State"] = {"Status": "exited", "Running": False}
        elif "/start" in path:
            if container_id == "replacement" and self.fail_start:
                raise RuntimeError("Start failed")
            value["State"] = {"Status": "running", "Running": True}
        elif "/rename" in path:
            name = urllib.parse.parse_qs(urllib.parse.urlsplit(path).query)["name"][0]
            value["Name"] = "/" + name
        elif "/update" in path:
            value["HostConfig"]["RestartPolicy"] = payload["RestartPolicy"]
        return {}


class DockerControlTests(unittest.TestCase):
    def test_bind_propagation_selinux_and_volume_nocopy_are_retained(self):
        inspection = copy.deepcopy(INSPECT)
        inspection["HostConfig"]["Binds"] = ["/srv/data:/data:ro,rslave,z", "anonymous:/cache:rw,nocopy"]
        inspection["Mounts"] = [{"Type": "bind", "Source": "/srv/data", "Destination": "/data", "RW": False, "Propagation": "rslave"}]
        draft = copy.deepcopy(DRAFT)
        draft["mounts"] = [{"type": "bind", "source": "/srv/data", "target": "/data", "readonly": True}, {"type": "volume", "source": "anonymous", "target": "/cache", "readonly": False}]
        payload = module.create_payload(inspection, draft)
        self.assertEqual(["/srv/data:/data:ro,rslave,z"], payload["HostConfig"]["Binds"])
        self.assertTrue(payload["HostConfig"]["Mounts"][0]["VolumeOptions"]["NoCopy"])

    def test_payload_preserves_image_volumes_security_resources_and_static_network(self):
        payload = module.create_payload(INSPECT, DRAFT)
        self.assertEqual("sha256:local-image", payload["Image"])
        self.assertEqual(1024, payload["HostConfig"]["Memory"])
        self.assertEqual(["NET_ADMIN"], payload["HostConfig"]["CapAdd"])
        self.assertEqual("anonymous", payload["HostConfig"]["Mounts"][0]["Source"])
        self.assertTrue(payload["HostConfig"]["Mounts"][0]["ReadOnly"])
        endpoint = payload["NetworkingConfig"]["EndpointsConfig"]["app"]
        self.assertEqual(["web", "custom"], endpoint["Aliases"])
        self.assertEqual("172.18.0.2", endpoint["IPAMConfig"]["IPv4Address"])
        self.assertNotIn("IPAddress", endpoint)
        self.assertEqual(INSPECT["Config"]["Env"], ["SECRET=a=b"])

    def test_success_retains_old_container_without_autorestart(self):
        docker = FakeDocker()
        result = module.recreate(docker, {"inspection": INSPECT, "draft": DRAFT})
        self.assertTrue(result["ok"])
        old = docker.containers[INSPECT["Id"]]
        self.assertFalse(old["State"]["Running"])
        self.assertEqual("no", old["HostConfig"]["RestartPolicy"]["Name"])
        self.assertTrue(old["Name"].startswith("/web-omv-backup-"))
        self.assertTrue(docker.containers["replacement"]["State"]["Running"])

    def test_create_failure_restores_name_running_state_and_restart_policy(self):
        docker = FakeDocker(fail_create=True)
        with self.assertRaisesRegex(RuntimeError, "Port already in use"):
            module.recreate(docker, {"inspection": INSPECT, "draft": DRAFT})
        self.assertEqual("/web", docker.containers[INSPECT["Id"]]["Name"])
        self.assertTrue(docker.containers[INSPECT["Id"]]["State"]["Running"])
        self.assertEqual("always", docker.containers[INSPECT["Id"]]["HostConfig"]["RestartPolicy"]["Name"])

    def test_start_failure_removes_only_replacement_and_keeps_volumes(self):
        docker = FakeDocker(fail_start=True)
        with self.assertRaisesRegex(RuntimeError, "Start failed"):
            module.recreate(docker, {"inspection": INSPECT, "draft": DRAFT})
        self.assertNotIn("replacement", docker.containers)
        self.assertTrue(docker.containers[INSPECT["Id"]]["State"]["Running"])
        self.assertTrue(any(method == "DELETE" and "v=false" in path for method, path, _ in docker.calls))

    def test_changed_config_aborts_before_stop(self):
        docker = FakeDocker()
        docker.containers[INSPECT["Id"]]["Config"]["Env"] = ["changed=true"]
        with self.assertRaisesRegex(RuntimeError, "changed since"):
            module.recreate(docker, {"inspection": INSPECT, "draft": DRAFT})
        self.assertEqual(1, len(docker.calls))

    def test_restore_retains_replacement_and_restores_original_policy(self):
        docker = FakeDocker()
        result = module.recreate(docker, {"inspection": INSPECT, "draft": DRAFT})
        request = dict(result, restart=result["old_restart"], retries=result["old_retries"])
        module.rollback(docker, request)
        self.assertEqual("/web", docker.containers[INSPECT["Id"]]["Name"])
        self.assertTrue(docker.containers[INSPECT["Id"]]["State"]["Running"])
        self.assertEqual("always", docker.containers[INSPECT["Id"]]["HostConfig"]["RestartPolicy"]["Name"])
        self.assertFalse(docker.containers["replacement"]["State"]["Running"])
        self.assertEqual("no", docker.containers["replacement"]["HostConfig"]["RestartPolicy"]["Name"])


if __name__ == "__main__":
    unittest.main()
