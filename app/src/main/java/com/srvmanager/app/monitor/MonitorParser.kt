package com.srvmanager.app.monitor

data class DiskInfo(
    val filesystem: String,
    val mount: String,
    val totalKb: Long,
    val usedKb: Long,
    val availKb: Long,
) {
    val usage: Float get() = if (totalKb > 0) usedKb.toFloat() / totalKb else 0f
}

data class ProcInfo(val pid: String, val name: String, val cpu: String, val mem: String)

data class MonitorSnapshot(
    val hostname: String,
    val os: String,
    val kernel: String,
    val cpuModel: String,
    val uptimeSec: Long,
    val load1: Double,
    val load5: Double,
    val load15: Double,
    val cores: Int,
    val cpuUsage: Float,
    val memTotalKb: Long,
    val memAvailKb: Long,
    val swapTotalKb: Long,
    val swapFreeKb: Long,
    val disks: List<DiskInfo>,
    val rxRate: Long,
    val txRate: Long,
    val processes: List<ProcInfo>,
    val users: Int,
) {
    val memUsedKb: Long get() = (memTotalKb - memAvailKb).coerceAtLeast(0)
    val memUsage: Float get() = if (memTotalKb > 0) memUsedKb.toFloat() / memTotalKb else 0f
    val swapUsedKb: Long get() = (swapTotalKb - swapFreeKb).coerceAtLeast(0)
    val swapUsage: Float get() = if (swapTotalKb > 0) swapUsedKb.toFloat() / swapTotalKb else 0f
}

/** Разбор вывода assets/scripts/monitor.sh (секции, начинающиеся с "@@"). */
object MonitorParser {
    private val ws = Regex("\\s+")
    private val virtualIfacePrefixes = listOf("lo", "veth", "docker", "br-", "virbr", "cni", "flannel", "kube", "tun", "wg")

    fun parse(output: String): MonitorSnapshot {
        val sections = HashMap<String, MutableList<String>>()
        var current: MutableList<String>? = null
        for (line in output.lines()) {
            if (line.startsWith("@@")) {
                val list = mutableListOf<String>()
                sections[line.removePrefix("@@").trim()] = list
                current = list
            } else {
                current?.add(line)
            }
        }
        fun first(name: String) = sections[name]?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()

        val load = first("LOAD").split(ws)
        val cpu1 = cpuTimes(first("STAT1"))
        val cpu2 = cpuTimes(first("STAT2"))
        val cpuUsage = if (cpu1 != null && cpu2 != null) {
            val total = cpu2.first - cpu1.first
            val idle = cpu2.second - cpu1.second
            if (total > 0) ((total - idle).toFloat() / total).coerceIn(0f, 1f) else 0f
        } else {
            0f
        }
        val mem = meminfo(sections["MEM"].orEmpty())
        val net1 = netTotals(sections["NET1"].orEmpty())
        val net2 = netTotals(sections["NET2"].orEmpty())

        return MonitorSnapshot(
            hostname = first("HOST"),
            os = first("OS"),
            kernel = first("KERNEL"),
            cpuModel = first("CPUMODEL"),
            uptimeSec = first("UPTIME").split(ws).firstOrNull()?.toDoubleOrNull()?.toLong() ?: 0L,
            load1 = load.getOrNull(0)?.toDoubleOrNull() ?: 0.0,
            load5 = load.getOrNull(1)?.toDoubleOrNull() ?: 0.0,
            load15 = load.getOrNull(2)?.toDoubleOrNull() ?: 0.0,
            cores = first("NPROC").toIntOrNull() ?: 1,
            cpuUsage = cpuUsage,
            memTotalKb = mem["MemTotal"] ?: 0L,
            memAvailKb = mem["MemAvailable"]
                ?: ((mem["MemFree"] ?: 0L) + (mem["Buffers"] ?: 0L) + (mem["Cached"] ?: 0L)),
            swapTotalKb = mem["SwapTotal"] ?: 0L,
            swapFreeKb = mem["SwapFree"] ?: 0L,
            disks = disks(sections["DISK"].orEmpty()),
            rxRate = (net2.first - net1.first).coerceAtLeast(0),
            txRate = (net2.second - net1.second).coerceAtLeast(0),
            processes = processes(sections["PS"].orEmpty()),
            users = first("USERS").toIntOrNull() ?: 0,
        )
    }

    /** (total, idle) из строки "cpu ..." файла /proc/stat. */
    private fun cpuTimes(line: String): Pair<Long, Long>? {
        val parts = line.split(ws)
        if (parts.isEmpty() || parts[0] != "cpu") return null
        val values = parts.drop(1).mapNotNull { it.toLongOrNull() }
        if (values.size < 4) return null
        val idle = values[3] + (values.getOrNull(4) ?: 0L)
        val total = values.take(8).sum()
        return total to idle
    }

    private fun meminfo(lines: List<String>): Map<String, Long> {
        val map = HashMap<String, Long>()
        for (line in lines) {
            val index = line.indexOf(':')
            if (index <= 0) continue
            val value = line.substring(index + 1).trim().split(ws).firstOrNull()?.toLongOrNull() ?: continue
            map[line.substring(0, index).trim()] = value
        }
        return map
    }

    private fun netTotals(lines: List<String>): Pair<Long, Long> {
        var rx = 0L
        var tx = 0L
        for (line in lines) {
            val index = line.indexOf(':')
            if (index < 0) continue
            val iface = line.substring(0, index).trim()
            if (iface.isEmpty() || virtualIfacePrefixes.any { iface.startsWith(it) }) continue
            val fields = line.substring(index + 1).trim().split(ws)
            if (fields.size < 9) continue
            rx += fields[0].toLongOrNull() ?: 0L
            tx += fields[8].toLongOrNull() ?: 0L
        }
        return rx to tx
    }

    private fun disks(lines: List<String>): List<DiskInfo> {
        val result = LinkedHashMap<String, DiskInfo>()
        for (line in lines.drop(1)) {
            val parts = line.trim().split(ws, limit = 6)
            if (parts.size < 6) continue
            val fs = parts[0]
            val mount = parts[5]
            val physical = fs.startsWith("/dev/") && !fs.startsWith("/dev/loop")
            if (!physical && mount != "/") continue
            if (result.containsKey(fs)) continue
            result[fs] = DiskInfo(
                filesystem = fs,
                mount = mount,
                totalKb = parts[1].toLongOrNull() ?: 0L,
                usedKb = parts[2].toLongOrNull() ?: 0L,
                availKb = parts[3].toLongOrNull() ?: 0L,
            )
        }
        return result.values.toList()
    }

    private fun processes(lines: List<String>): List<ProcInfo> =
        lines.drop(1).mapNotNull { line ->
            val parts = line.trim().split(ws)
            if (parts.size < 4) {
                null
            } else {
                ProcInfo(
                    pid = parts[0],
                    name = parts.subList(1, parts.size - 2).joinToString(" "),
                    cpu = parts[parts.size - 2],
                    mem = parts[parts.size - 1],
                )
            }
        }
}
