package com.omvcompanion.app.data.repo

import com.omvcompanion.app.data.rpc.str
import kotlinx.serialization.json.*

internal fun mergeLiveInterfaces(configured: List<JsonObject>, live: List<JsonObject>): List<JsonObject> =
    configured.map { config ->
        val runtime = live.firstOrNull { it.str("devicename") == config.str("devicename") }
        JsonObject(config + (runtime ?: emptyMap()) + mapOf("uuid" to (config["uuid"] ?: JsonNull),
            "method" to (config["method"] ?: JsonNull), "_runtime_available" to JsonPrimitive(runtime != null)))
    }

internal val LIVE_DNS_SCRIPT = """
import json, os, re, subprocess
interfaces={}; global_dns=[]
try:
 result=subprocess.run(['resolvectl','dns'],capture_output=True,text=True,timeout=4)
 if result.returncode==0:
  for line in result.stdout.splitlines():
   match=re.match(r'Link \d+ \(([^)]+)\):\s*(.*)',line)
   if match: interfaces[match.group(1)]=match.group(2).split()
   elif line.startswith('Global:'): global_dns=line.split(':',1)[1].split()
except (OSError,subprocess.TimeoutExpired): pass
for name in os.listdir('/sys/class/net'):
 try:
  index=open('/sys/class/net/'+name+'/ifindex').read().strip()
  for line in open('/run/systemd/netif/leases/'+index):
   if line.startswith('DNS=') and not interfaces.get(name): interfaces[name]=line.strip()[4:].split()
 except OSError: pass
if not global_dns:
 for path in ['/run/systemd/resolve/resolv.conf','/etc/resolv.conf']:
  try:
   servers=[line.split()[1] for line in open(path) if line.strip().startswith('nameserver ') and len(line.split())>1]
   servers=[ip for ip in servers if not ip.startswith('127.') and ip!='::1']
   if servers: global_dns=servers; break
  except OSError: pass
print(json.dumps({'interfaces':interfaces,'global':global_dns}))
""".trimIndent()
