"""Docker Engine operations over the local Unix socket. JSON request arrives on stdin.

Recreation retains the old stopped container and uses the existing image ID and
named volumes. Failure restores the original name and running state where possible.
No Docker TCP endpoint or third-party Python dependency is required.
"""
import copy
import http.client
import json
import socket
import sys
import time
import urllib.parse
import uuid


class UnixConnection(http.client.HTTPConnection):
    def __init__(self):
        super().__init__("localhost", timeout=35)

    def connect(self):
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.settimeout(self.timeout)
        self.sock.connect("/var/run/docker.sock")


class Docker:
    def __init__(self):
        version = self.request("GET", "/version")
        api = version["ApiVersion"]
        if not all(p.isdigit() for p in api.split(".")):
            raise RuntimeError("Unexpected Docker API version")
        self.prefix = "/v" + api

    def request(self, method, path, payload=None):
        connection = UnixConnection()
        try:
            body = json.dumps(payload) if payload is not None else None
            connection.request(method, getattr(self, "prefix", "") + path, body, {"Content-Type": "application/json"})
            response = connection.getresponse()
            raw = response.read()
            result = json.loads(raw) if raw else {}
            if response.status >= 400:
                raise RuntimeError(result.get("message", "Docker request failed"))
            return result
        finally:
            connection.close()


def encoded(value):
    return urllib.parse.quote(value, safe="")


def stable_configuration(inspection):
    return {key: inspection.get(key) for key in ("Id", "Config", "HostConfig", "Mounts")}


def create_payload(inspection, draft):
    config = copy.deepcopy(inspection["Config"])
    host = copy.deepcopy(inspection["HostConfig"])
    config["Image"] = inspection["Image"]  # Never silently pull or switch the image.
    config["Env"] = draft["environment"]
    bindings = {}
    exposed = dict(config.get("ExposedPorts") or {})
    for port in draft["ports"]:
        key = port["container"] + "/" + port["protocol"]
        exposed[key] = {}
        bindings.setdefault(key, []).append({"HostIp": port["address"], "HostPort": port["host"]})
    config["ExposedPorts"] = exposed
    host["PortBindings"] = bindings
    host["RestartPolicy"] = {"Name": draft["restart"], "MaximumRetryCount": draft["retries"] if draft["restart"] == "on-failure" else 0}
    original_mounts = {m["Target"]: m for m in host.get("Mounts") or []}
    bind_options = {}
    for bind in host.get("Binds") or []:
        parts = bind.split(":")
        if len(parts) >= 2:
            bind_options[parts[1]] = [option for option in (parts[2].split(",") if len(parts) > 2 else []) if option not in ("ro", "rw")]
    inspected_mounts = {m["Destination"]: m for m in inspection.get("Mounts", [])}
    host["Binds"] = []
    mounts = []
    for mount in draft["mounts"]:
        original = original_mounts.get(mount["target"], {})
        if mount["type"] == "bind" and not original:
            options = bind_options.get(mount["target"], []).copy()
            propagation = inspected_mounts.get(mount["target"], {}).get("Propagation")
            if propagation and not any(option in ("private", "rprivate", "shared", "rshared", "slave", "rslave") for option in options):
                options.append(propagation)
            options.insert(0, "ro" if mount["readonly"] else "rw")
            host["Binds"].append(mount["source"] + ":" + mount["target"] + ":" + ",".join(options))
            continue
        value = copy.deepcopy(original) if original.get("Type") == mount["type"] else {}
        value.update(Type=mount["type"], Source=mount["source"], Target=mount["target"], ReadOnly=mount["readonly"])
        if mount["type"] == "volume" and "nocopy" in bind_options.get(mount["target"], []):
            value.setdefault("VolumeOptions", {})["NoCopy"] = True
        mounts.append(value)
    # Preserve tmpfs and other mount types that this editor does not expose.
    mounts.extend(copy.deepcopy(m) for m in host.get("Mounts") or [] if m["Type"] not in ("bind", "volume"))
    host["Mounts"] = mounts
    targets = {m["target"] for m in draft["mounts"]}
    old_targets = {m["Destination"] for m in inspection.get("Mounts", []) if m["Type"] in ("bind", "volume")}
    config["Volumes"] = {k: v for k, v in (config.get("Volumes") or {}).items() if k not in old_targets or k in targets}
    endpoints = {}
    if host.get("NetworkMode") not in ("host", "none"):
        for network, old in inspection.get("NetworkSettings", {}).get("Networks", {}).items():
            endpoint = {key: copy.deepcopy(old[key]) for key in ("IPAMConfig", "Links", "DriverOpts", "GwPriority") if old.get(key) is not None}
            aliases = [alias for alias in old.get("Aliases") or [] if alias not in (inspection["Id"], inspection["Id"][:12])]
            if aliases:
                endpoint["Aliases"] = aliases
            endpoints[network] = endpoint
    config.update(HostConfig=host, NetworkingConfig={"EndpointsConfig": endpoints})
    return config


def recreate(docker, request):
    expected = request["inspection"]
    container_id = expected["Id"]
    current = docker.request("GET", "/containers/" + encoded(container_id) + "/json")
    if stable_configuration(current) != stable_configuration(expected):
        raise RuntimeError("Container configuration changed since this editor opened. Reload and review the new settings.")
    labels = current["Config"].get("Labels") or {}
    if labels.get("com.docker.swarm.service.id") or current["HostConfig"].get("NetworkMode", "").startswith("container:"):
        raise RuntimeError("Edit this container through its deployment definition.")
    if current["State"]["Status"] not in ("running", "exited", "created"):
        raise RuntimeError("Stop or resume this container before editing.")
    if current["Name"] != expected["Name"]:
        raise RuntimeError("Container name changed. Reload the editor.")
    payload = create_payload(current, request["draft"])
    original_name = current["Name"].lstrip("/")
    backup_name = original_name + "-omv-backup-" + uuid.uuid4().hex[:8]
    running = current["State"].get("Running", False)
    old_restart = current["HostConfig"].get("RestartPolicy") or {"Name": "no", "MaximumRetryCount": 0}
    new_id = None
    renamed = False
    stopped = False
    try:
        if running:
            docker.request("POST", "/containers/" + encoded(container_id) + "/stop?t=20")
            stopped = True
        docker.request("POST", "/containers/" + encoded(container_id) + "/update", {"RestartPolicy": {"Name": "no", "MaximumRetryCount": 0}})
        docker.request("POST", "/containers/" + encoded(container_id) + "/rename?name=" + encoded(backup_name))
        renamed = True
        new_id = docker.request("POST", "/containers/create?name=" + encoded(original_name), payload)["Id"]
        if running:
            docker.request("POST", "/containers/" + encoded(new_id) + "/start")
        return {"ok": True, "new_id": new_id, "backup_id": container_id, "backup_name": backup_name, "original_name": original_name, "was_running": running, "old_restart": old_restart["Name"], "old_retries": old_restart.get("MaximumRetryCount", 0)}
    except Exception as error:
        recovery_errors = []
        if new_id:
            try:
                docker.request("DELETE", "/containers/" + encoded(new_id) + "?force=true&v=false")
            except Exception:
                recovery_errors.append("replacement could not be removed")
        if renamed:
            try:
                docker.request("POST", "/containers/" + encoded(container_id) + "/rename?name=" + encoded(original_name))
            except Exception:
                recovery_errors.append("original name could not be restored")
        try:
            docker.request("POST", "/containers/" + encoded(container_id) + "/update", {"RestartPolicy": old_restart})
        except Exception:
            recovery_errors.append("original restart policy could not be restored")
        if stopped:
            try:
                docker.request("POST", "/containers/" + encoded(container_id) + "/start")
            except Exception:
                recovery_errors.append("original container could not be started")
        suffix = "; recovery needs attention: " + ", ".join(recovery_errors) if recovery_errors else "; original container retained"
        raise RuntimeError(str(error) + suffix)


def rollback(docker, request):
    new_id = request["new_id"]
    old_id = request["backup_id"]
    new = docker.request("GET", "/containers/" + encoded(new_id) + "/json")
    old = docker.request("GET", "/containers/" + encoded(old_id) + "/json")
    name = request["original_name"]
    if new["Name"].lstrip("/") != name or not old["Name"].lstrip("/").startswith(name + "-omv-backup-"):
        raise RuntimeError("Container names changed. Review and restore the backup manually.")
    if old["State"].get("Running"):
        raise RuntimeError("The backup is already running. Review containers before restoring it.")
    running = new["State"].get("Running", False)
    replacement_name = name + "-omv-replaced-" + uuid.uuid4().hex[:8]
    renamed = False
    restored = False
    old_started = False
    replacement_policy = new["HostConfig"].get("RestartPolicy") or {"Name": "no", "MaximumRetryCount": 0}
    try:
        if running:
            docker.request("POST", "/containers/" + encoded(new_id) + "/stop?t=20")
        docker.request("POST", "/containers/" + encoded(new_id) + "/update", {"RestartPolicy": {"Name": "no", "MaximumRetryCount": 0}})
        docker.request("POST", "/containers/" + encoded(new_id) + "/rename?name=" + encoded(replacement_name))
        renamed = True
        docker.request("POST", "/containers/" + encoded(old_id) + "/rename?name=" + encoded(name))
        restored = True
        docker.request("POST", "/containers/" + encoded(old_id) + "/update", {"RestartPolicy": {"Name": request["restart"], "MaximumRetryCount": request["retries"]}})
        if request["was_running"]:
            docker.request("POST", "/containers/" + encoded(old_id) + "/start")
            old_started = True
        return {"ok": True, "retained_replacement": replacement_name}
    except Exception:
        # Never delete either container. Put the replacement back best-effort.
        recovery = []
        if old_started:
            recovery.append(("POST", "/containers/" + encoded(old_id) + "/stop?t=20", None))
        recovery.append(("POST", "/containers/" + encoded(old_id) + "/update", {"RestartPolicy": {"Name": "no", "MaximumRetryCount": 0}}))
        if restored:
            recovery.append(("POST", "/containers/" + encoded(old_id) + "/rename?name=" + encoded(old["Name"].lstrip("/")), None))
        if renamed:
            recovery.append(("POST", "/containers/" + encoded(new_id) + "/rename?name=" + encoded(name), None))
        recovery.append(("POST", "/containers/" + encoded(new_id) + "/update", {"RestartPolicy": replacement_policy}))
        if running:
            recovery.append(("POST", "/containers/" + encoded(new_id) + "/start", None))
        for method, path, payload in recovery:
            try:
                docker.request(method, path, payload)
            except Exception:
                pass
        raise RuntimeError("Restore failed. Both containers are retained; inspect their names and state before retrying.")



def main():
    try:
        request = json.load(sys.stdin)
        docker = Docker()
        if request["action"] == "recreate":
            result = recreate(docker, request)
        elif request["action"] == "rollback":
            result = rollback(docker, request)
        else:
            raise RuntimeError("Unsupported Docker operation")
        print(json.dumps(result))
    except Exception as error:
        print(json.dumps({"ok": False, "error": str(error)}))
        sys.exit(1)


if __name__ == "__main__":
    main()
