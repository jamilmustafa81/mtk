package app.mtk
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

class Api(host: String, port: Int, user: String, pw: String) {
    private val s = Socket().apply { connect(InetSocketAddress(host, port), 6000); soTimeout = 12000 }
    private val i = DataInputStream(BufferedInputStream(s.getInputStream()))
    private val o = s.getOutputStream()
    init { cmd(listOf("/login", "=name=$user", "=password=$pw")) }
    fun close() { try { s.close() } catch (e: Exception) {} }
    private fun w(x: String) {
        val b = x.toByteArray(); val n = b.size
        when {
            n < 0x80 -> o.write(n)
            n < 0x4000 -> { o.write((n or 0x8000) shr 8); o.write(n and 0xFF) }
            else -> { o.write((n or 0xC00000) shr 16); o.write((n shr 8) and 0xFF); o.write(n and 0xFF) }
        }
        o.write(b)
    }
    private fun rw(): String {
        val b = i.readUnsignedByte()
        val n = when {
            b < 0x80 -> b
            b < 0xC0 -> ((b and 0x3F) shl 8) or i.readUnsignedByte()
            b < 0xE0 -> { val x = i.readUnsignedByte(); val y = i.readUnsignedByte(); ((b and 0x1F) shl 16) or (x shl 8) or y }
            else -> { val x = i.readUnsignedByte(); val y = i.readUnsignedByte(); val z = i.readUnsignedByte(); ((b and 0x0F) shl 24) or (x shl 16) or (y shl 8) or z }
        }
        val buf = ByteArray(n); i.readFully(buf); return String(buf)
    }
    @Synchronized fun cmd(words: List<String>): List<Map<String, String>> {
        for (x in words) w(x)
        w(""); o.flush()
        val out = ArrayList<Map<String, String>>(); var err: String? = null
        while (true) {
            val sent = ArrayList<String>()
            while (true) { val x = rw(); if (x.isEmpty()) break; sent.add(x) }
            if (sent.isEmpty()) continue
            val d = HashMap<String, String>()
            for (x in sent.drop(1)) if (x.startsWith("=")) { val t = x.substring(1); d[t.substringBefore("=")] = t.substringAfter("=", "") }
            when (sent[0]) {
                "!re" -> out.add(d)
                "!trap" -> err = d["message"] ?: "error"
                "!done" -> { if (err != null) throw RuntimeException(err); return out }
            }
        }
    }
}

object B {
    private lateinit var dir: File
    private var started = false
    private var cfg = JSONObject("""{"host":"192.168.88.1","port":8728,"user":"admin","password":"","wan":"","ping":"8.8.8.8"}""")
    private val S = HashMap<String, Any?>()
    private val events = ArrayList<Map<String, String>>()
    private val hist = ArrayList<List<Long>>()
    private var poll: Api? = null
    private var act: Api? = null

    fun start(d: File) {
        if (started) return
        started = true; dir = d
        try { cfg = JSONObject(File(d, "config.json").readText()) } catch (e: Exception) {}
        try {
            val a = JSONArray(File(d, "events.json").readText())
            for (k in 0 until a.length()) { val o = a.getJSONObject(k); events.add(mapOf("t" to o.getString("t"), "name" to o.getString("name"), "type" to o.getString("type"), "ip" to o.optString("ip"), "mac" to o.optString("mac"))) }
        } catch (e: Exception) {}
        synchronized(S) { S["ok"] = false; S["err"] = "جارٍ الاتصال..."; S["events"] = ArrayList(events); S["total"] = 0 }
        Thread { loop() }.apply { isDaemon = true }.start()
    }
    private fun up(k: String, v: Any?) { synchronized(S) { S[k] = v } }

    private fun cmd(k: Int, w: List<String>): List<Map<String, String>> {
        try {
            var a = if (k == 0) poll else act
            if (a == null) {
                a = Api(cfg.getString("host"), cfg.optInt("port", 8728), cfg.getString("user"), cfg.getString("password"))
                if (k == 0) poll = a else act = a
            }
            return a.cmd(w)
        } catch (e: IOException) { if (k == 0) poll = null else act = null; throw e }
    }
    private fun ev(t: String, n: String, ty: String, a: Map<String, String>) =
        mapOf("t" to t, "name" to n, "type" to ty, "ip" to (a["address"] ?: ""), "mac" to (a["caller-id"] ?: ""))

    private fun loop() {
        val pb = HashMap<String, LongArray>(); var pt = 0L; var pa: Map<String, Map<String, String>>? = null; var n = 0
        while (true) {
            try {
                val r = cmd(0, listOf("/system/resource/print"))[0]
                if (n % 30 == 0) up("ident", cmd(0, listOf("/system/identity/print"))[0]["name"] ?: "")
                val ifs = cmd(0, listOf("/interface/print", "=.proplist=name,rx-byte,tx-byte"))
                val now = System.currentTimeMillis(); val dt = maxOf((now - pt) / 1000.0, 0.5); val rates = HashMap<String, DoubleArray>()
                for (i in ifs) {
                    val nm = i["name"] ?: continue
                    val rx = i["rx-byte"]?.toLongOrNull() ?: 0L; val tx = i["tx-byte"]?.toLongOrNull() ?: 0L
                    val p = pb[nm]
                    if (p != null && pt > 0) rates[nm] = doubleArrayOf(maxOf(rx - p[0], 0L) * 8 / dt, maxOf(tx - p[1], 0L) * 8 / dt)
                    pb[nm] = longArrayOf(rx, tx)
                }
                pt = now
                if (cfg.optString("wan").isEmpty()) for (rt in cmd(0, listOf("/ip/route/print", "?dst-address=0.0.0.0/0"))) {
                    val m = Regex("via (\\S+)").find(rt["gateway-status"] ?: "")
                    if (m != null && rt["active"] == "true") { cfg.put("wan", m.groupValues[1]); break }
                }
                val wan = cfg.optString("wan"); val wr = rates[wan]
                if (n % 3 == 0) {
                    val t = cmd(0, listOf("/ping", "=address=" + cfg.optString("ping", "8.8.8.8"), "=count=1")).mapNotNull { it["time"] }
                    up("net", mapOf<String, Any?>("up" to t.isNotEmpty(), "ms" to (t.lastOrNull() ?: "")))
                }
                val q = HashMap<String, LongArray>()
                for (x in cmd(0, listOf("/queue/simple/print", "=.proplist=name,rate"))) {
                    val nm = (x["name"] ?: "").replace(Regex("^<(pppoe-)?|>$"), ""); val p = (x["rate"] ?: "0/0").split("/")
                    q[nm] = longArrayOf(p.getOrNull(0)?.toLongOrNull() ?: 0L, p.getOrNull(1)?.toLongOrNull() ?: 0L)
                }
                val ac = cmd(0, listOf("/ppp/active/print")); val cur = ac.associateBy { it["name"] ?: "" }
                val prev = pa
                if (prev != null) {
                    val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()); var ch = false
                    for (k in cur.keys - prev.keys) { events.add(ev(ts, k, "up", cur[k]!!)); ch = true }
                    for (k in prev.keys - cur.keys) { events.add(ev(ts, k, "down", prev[k]!!)); ch = true }
                    if (ch) { while (events.size > 300) events.removeAt(0); File(dir, "events.json").writeText(JSONArray(events).toString()) }
                }
                pa = cur
                if (n % 5 == 0) up("total", cmd(0, listOf("/ppp/secret/print", "=.proplist=name")).size)
                hist.add(listOf(Math.round(wr?.get(0) ?: 0.0), Math.round(wr?.get(1) ?: 0.0))); while (hist.size > 120) hist.removeAt(0)
                synchronized(S) {
                    S["hist"] = ArrayList(hist); S["events"] = ArrayList(events)
                    S["active"] = ac.map {
                        val nm = it["name"] ?: ""
                        mapOf<String, Any?>("id" to it[".id"], "name" to nm, "ip" to (it["address"] ?: ""), "mac" to (it["caller-id"] ?: ""),
                            "uptime" to (it["uptime"] ?: ""), "up" to (q[nm]?.get(0) ?: 0L), "down" to (q[nm]?.get(1) ?: 0L))
                    }
                    S["ok"] = true; S["err"] = ""; S["cpu"] = r["cpu-load"] ?: ""; S["mf"] = r["free-memory"]?.toLongOrNull() ?: 0L
                    S["mt"] = r["total-memory"]?.toLongOrNull() ?: 0L; S["uptime"] = r["uptime"] ?: ""; S["board"] = r["board-name"] ?: ""
                    S["ver"] = r["version"] ?: ""; S["wan"] = wan; S["down"] = wr?.get(0) ?: 0.0; S["upl"] = wr?.get(1) ?: 0.0
                    S["ifaces"] = ifs.map { it["name"] ?: "" }
                }
                n++; Thread.sleep(1500)
            } catch (e: Exception) {
                up("ok", false); up("err", e.message ?: "تعذر الاتصال"); pa = null; pt = 0; Thread.sleep(3000)
            }
        }
    }

    private fun doOp(b: JSONObject) {
        val f = listOf("name", "password", "profile", "comment").filter { b.has(it) }.map { "=" + it + "=" + b.getString(it) }
        when (b.getString("op")) {
            "add" -> cmd(1, listOf("/ppp/secret/add", "=service=pppoe") + f)
            "set" -> cmd(1, listOf("/ppp/secret/set", "=.id=" + b.getString("id")) + f)
            "toggle" -> cmd(1, listOf("/ppp/secret/" + (if (b.getBoolean("disable")) "disable" else "enable"), "=.id=" + b.getString("id")))
            "remove" -> cmd(1, listOf("/ppp/secret/remove", "=.id=" + b.getString("id")))
            "kick" -> for (a in cmd(1, listOf("/ppp/active/print", "?name=" + b.getString("name")))) cmd(1, listOf("/ppp/active/remove", "=.id=" + a[".id"]))
        }
    }

    fun handle(p: String, b: String): String {
        var code = 200
        val out: String = try {
            when (p) {
                "/api/live" -> synchronized(S) { JSONObject(S as Map<*, *>).toString() }
                "/api/cfg" -> if (b.isNotEmpty()) {
                    val j = JSONObject(b)
                    for (k in listOf("host", "port", "user", "wan", "ping")) if (j.has(k)) cfg.put(k, j.get(k))
                    if (j.optString("password").isNotEmpty()) cfg.put("password", j.getString("password"))
                    File(dir, "config.json").writeText(cfg.toString())
                    poll?.close(); act?.close(); poll = null; act = null; hist.clear()
                    "{\"done\":true}"
                } else JSONObject(cfg.toString()).apply { remove("password") }.toString()
                "/api/secrets" -> {
                    val on = HashMap<String, String>()
                    synchronized(S) { for (a in (S["active"] as? List<*>) ?: emptyList<Any>()) { val m = a as Map<*, *>; on[m["name"].toString()] = m["ip"].toString() } }
                    JSONArray(cmd(1, listOf("/ppp/secret/print")).map {
                        val nm = it["name"] ?: ""
                        mapOf<String, Any?>("id" to it[".id"], "name" to nm, "password" to (it["password"] ?: ""), "profile" to (it["profile"] ?: ""),
                            "comment" to (it["comment"] ?: ""), "disabled" to (it["disabled"] == "true"), "online" to on.containsKey(nm), "ip" to (on[nm] ?: ""))
                    }).toString()
                }
                "/api/profiles" -> JSONArray(cmd(1, listOf("/ppp/profile/print", "=.proplist=name")).map { it["name"] ?: "" }).toString()
                "/api/do" -> { doOp(JSONObject(b)); "{\"done\":true}" }
                else -> { code = 404; "{}" }
            }
        } catch (e: Exception) { code = 400; JSONObject().put("error", e.message ?: "error").toString() }
        return "{\"s\":$code,\"b\":$out}"
    }
}
