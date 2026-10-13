package com.omvcompanion.app.data.rpc

import kotlinx.serialization.json.*

/** Isolated read-only fixtures. No HTTP call is made for the reserved demo host. */
internal object DemoRpc {
    private val json = Json
    private fun parse(text: String): JsonElement = json.parseToJsonElement(text)
    private fun list(text: String) = buildJsonObject { val data = parse(text); put("data", data); put("total", (data as? JsonArray)?.size ?: 0) }
    fun call(service: String, method: String, params: JsonObject): JsonElement = when ("$service.$method") {
        "Session.login" -> parse("""{"authenticated":true,"permissions":{"role":"admin"}}""")
        "Session.logout" -> JsonNull
        "System.getInformation" -> parse("""{"hostname":"demo-nas","version":"8.0 · Demo","kernel":"Linux 6.12","cpuModelName":"Intel Core i5-12600K","cpuUsage":14.2,"memTotal":17179869184,"memUsed":5368709120,"uptime":345600,"loadAverage":{"1min":0.22,"5min":0.18,"15min":0.16},"configDirty":false,"rebootRequired":false,"availablePkgUpdates":2,"cpuTemp":42}""")
        "Sensors.getTemperatures" -> parse("""[{"temp":42}]""")
        "DiskMgmt.getList" -> list("""[{"devicefile":"/dev/sda","model":"Samsung 870 EVO","serialnumber":"DEMO-SSD","size":1000000000000},{"devicefile":"/dev/sdb","model":"WD Red Plus","serialnumber":"DEMO-HDD","size":4000000000000}]""")
        "FileSystemMgmt.getList" -> list("""[{"uuid":"demo-data","devicefile":"/dev/sdb1","description":"Media library","dir":"/srv/media","type":"ext4","size":4000000000000,"available":1800000000000,"percentage":55,"usagewarnthreshold":85},{"uuid":"demo-system","devicefile":"/dev/sda1","description":"System","dir":"/","type":"ext4","size":1000000000000,"available":760000000000,"percentage":24}]""")
        "Smart.getList" -> list("""[{"devicefile":"/dev/sda","model":"Samsung 870 EVO","overallstatus":"GOOD","temperature":32},{"devicefile":"/dev/sdb","model":"WD Red Plus","overallstatus":"GOOD","temperature":38}]""")
        "Smart.getInformation" -> parse("""{"model":"Demo drive","serialnumber":"DEMO","temperature":32,"poweronhours":2400,"powercycles":120}""")
        "Smart.getAttributes" -> parse("""[{"id":5,"name":"Reallocated sectors","value":100,"worst":100,"threshold":10,"rawvalue":0},{"id":194,"name":"Temperature","rawvalue":32}]""")
        "Smart.getSelfTestLogs" -> parse("""[{"type":"Short offline","status":"Completed without error","lifetime":2390}]""")
        "ShareMgmt.getList" -> list("""[{"uuid":"demo-share","name":"Media","description":"Movies and photos","absdirpath":"/srv/media","device":"/dev/sdb1","status":"Available"}]""")
        "Services.getStatus" -> list("""[{"name":"ssh","title":"SSH","enabled":true,"running":true},{"name":"smb","title":"SMB / Windows shares","enabled":true,"running":true},{"name":"nfs","title":"NFS","enabled":false,"running":false}]""")
        "Compose.getContainerList" -> list("""[{"id":"demo-nextcloud","name":"Nextcloud","image":"nextcloud:stable","state":"running","status":"Up 4 days (healthy)","ports":"0.0.0.0:8080->80/tcp","mounts":"/srv/media/cloud:/var/www/html","network":"cloud"},{"id":"demo-jellyfin","name":"Jellyfin","image":"jellyfin/jellyfin:latest","state":"running","status":"Up 4 days","ports":"0.0.0.0:8096->8096/tcp"},{"id":"demo-redis","name":"Redis","image":"redis:7","state":"exited","status":"Exited (0)"}]""")
        "Compose.getFileList" -> list("""[{"uuid":"00000000-0000-4000-8000-000000000002","name":"Cloud","status":"UP","svcname":"app, database","image":"nextcloud:stable"}]""")
        "Compose.getImages" -> list("""[{"repo":"jellyfin/jellyfin","tag":"latest","status":"AVAILABLE","inuse":true},{"repo":"redis","tag":"7","status":"CURRENT","inuse":false}]""")
        "Compose.getServicesList" -> list("""[{"name":"nextcloud","project":"cloud","service":"app","state":"running","status":"Up 4 days","filepath":"/srv/compose/cloud/cloud.yml","envpath":"/srv/compose/cloud/cloud.env","overridepath":""}]""")
        "ImageCheck.getStatus" -> parse("""{"installed":true,"lastcheck":"Demo data"}""")
        "ImageCheck.getUpdateList" -> list("""[{"image":"jellyfin/jellyfin:latest","name":"Jellyfin","update_available":true,"reason":"Registry has a newer image digest"}]""")
        "Apt.getUpgradedList", "Apt.getUpgraded" -> list("""[{"name":"openmediavault","version":"8.0.4","repository":"OMV stable"},{"name":"linux-image-amd64","version":"6.12","repository":"Debian"}]""")
        "Plugin.getList" -> list("""[{"name":"openmediavault-compose","version":"8.0","abstract":"Docker Compose management","installed":true},{"name":"openmediavault-backup","version":"8.0","abstract":"System backup","installed":false}]""")
        "Network.getGeneralSettings" -> parse("""{"hostname":"demo-nas","domainname":"home"}""")
        "Network.getEthernetIface" -> parse("""{"uuid":"demo-network","devicename":"eth0","method":"dhcp","address":"","netmask":"","gateway":"","method6":"auto","address6":"","netmask6":64,"gateway6":"","mtu":0,"dnsnameservers":"","dnssearch":"","wol":false,"options":""}""")
        "Network.enumerateDevices", "Network.enumerateDevicesList" -> parse("""[{"devicename":"eth0","address":"192.168.1.100","method":"dhcp","gateway":"192.168.1.1"}]""")
        "Network.getInterfaceList" -> list("""[{"uuid":"demo-network","devicename":"eth0","method":"dhcp","address":"192.168.1.100","gateway":"192.168.1.1"}]""")
        "Config.isDirty" -> JsonPrimitive(false)
        "UserMgmt.getUserList", "UserMgmt.enumerateUsers" -> list("""[{"name":"alex","uid":1000,"groups":["users"],"comment":"Home user","email":"","shell":"/usr/sbin/nologin","disallowusermod":false,"sshpubkeys":[]}]""")
        "UserMgmt.getGroupList", "UserMgmt.enumerateAllGroups" -> list("""[{"name":"media","gid":1001,"members":["alex"],"comment":"Media library access"}]""")
        "UserMgmt.getUser" -> recordsDemo(call("UserMgmt", "getUserList", params))
        "UserMgmt.getGroup" -> recordsDemo(call("UserMgmt", "getGroupList", params))
        "Cron.getList" -> list("""[{"uuid":"00000000-0000-4000-8000-000000000001","comment":"Nightly backup","command":"/usr/local/bin/backup.sh","username":"root","enable":true,"sendemail":true,"execution":"daily","type":"userdefined","minute":"0","hour":"3","dayofmonth":"*","month":"*","dayofweek":"*"}]""")
        "Cron.get" -> recordsDemo(call("Cron", "getList", params))
        "System.getTopInfo" -> parse("""{"processes":[{"PID":1234,"USER":"root","COMMAND":"dockerd","%CPU":2.1,"%MEM":1.4,"RES":240000000},{"PID":4321,"USER":"www-data","COMMAND":"php-fpm","%CPU":0.5,"%MEM":0.8,"RES":140000000}]}""")
        "LogFile.getList" -> list("""[{"date":"Today 10:42","hostname":"demo-nas","process":"systemd","message":"Started Docker Application Container Engine."},{"date":"Today 10:40","hostname":"demo-nas","process":"sshd","message":"SSH service is ready."}]""")
        else -> throw OmvException("This action is unavailable in demo mode. Connect to your server to use it.")
    }
    private fun recordsDemo(value: JsonElement): JsonObject = ((value as JsonObject)["data"] as JsonArray).first() as JsonObject
}
