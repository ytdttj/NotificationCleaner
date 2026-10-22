package cc.ytdttj.noticleaner.data

import android.content.Context
import cc.ytdttj.noticleaner.ai.SpamDelta
import cc.ytdttj.noticleaner.ai.SpamModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 模型仓库（1.1.0，复刻 Notice 架构）：
 * - base：assets 内置的预训练模型，**只读，永不被改写**
 * - delta：端上学习得到的稀疏修正（filesDir/model/spam_delta.bin，NSPD 格式）
 * - effective：base.withDelta(delta)，打分与学习拟合都基于它
 *
 * [tunedFingerprint] 记录上次拟合所基于的 base 指纹；assets 基线升级后指纹变化，
 * 调用方据此用既有标注自动重新拟合（学习成果跨版本迁移）。
 */
class ModelRepository(private val context: Context) {
    private val mutex = Mutex()
    private val deltaFile: File
        get() = File(context.filesDir, "model/spam_delta.bin")
    private val fingerprintFile: File
        get() = File(context.filesDir, "model/tuned_fp")

    /** 学习 schema 版本文件（2.2.1 Dev 1）：拟合语义变化时用于触发一次性全量重拟合 */
    private val schemaFile: File
        get() = File(context.filesDir, "model/learn_schema")

    @Volatile
    private var base: SpamModel? = null

    /** P1-11：base 连续加载失败计数（进程内缓存，达上限后不再重试 IO） */
    @Volatile
    private var baseLoadFailedCount = 0

    @Volatile
    private var effective: SpamModel? = null

    /**
     * delta 版本（1.2.1）：学习修正每次变化时刷新（时间戳），推送给模块触发热重载。
     * 初始值：delta 文件已存在则为 1（模块 attach 时按此加载既有 delta），无则 0。
     */
    val deltaVersion = kotlinx.coroutines.flow.MutableStateFlow(
        if (deltaFile.exists()) 1L else 0L,
    )

    /**
     * 模型代数（1.2.0，ImprovePlan P1-3）：effective 每次重建时 +1，
     * 打分缓存以 (epoch, pkg, text, channel) 为键——模型更新后旧条目自然失效。
     */
    @Volatile
    private var modelEpoch = 0L

    /** 打分结果 LRU（P1-3：同文本重复推送免重复推理；64 条上限；P3-5：64 位哈希键，零字符串拼接） */
    private val scoreCache = object : LinkedHashMap<Long, Double>(SCORE_CACHE_MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Double>): Boolean =
            size > SCORE_CACHE_MAX
    }

    /** 内置基线模型（只读）；assets 缺失或损坏时为 null。 */
    suspend fun baseModel(): SpamModel? = mutex.withLock { loadBase() }

    /** 当前生效模型（base + 学习修正）；不可用时为 null。P2-1：双检免锁，命中时无 Mutex 开销 */
    suspend fun get(): SpamModel? {
        effective?.let { return it }
        return mutex.withLock {
            effective ?: run {
                val b = loadBase() ?: return@run null
                val d = runCatching { readDelta() }.getOrNull()
                (if (d != null) b.withDelta(d) else b).also { setEffective(it) }
            }
        }
    }

    /**
     * 打分（1.2.0，ImprovePlan P1-2/P1-3）：复用调用方已 normalize 的文本，
     * 结果带 LRU 缓存（键含模型代数，模型更新后自动失效）。
     * @return 概率；模型不可用时为 null（调用方按 0.0 处理）
     */
    suspend fun scoreCached(pkg: String, normalizedText: String, channelKey: Int?): Double? {
        val model = get() ?: return null
        val key = cacheKey(pkg, normalizedText, channelKey)
        synchronized(scoreCache) { scoreCache[key] }?.let { return it }
        // vc87：App 先验桶由 pkg 唯一决定（缓存键已含 pkg，键无需变化）
        val p = model.scoreNormalized(normalizedText, cc.ytdttj.noticleaner.ai.FeatureHasher.appKey(pkg), channelKey)
        synchronized(scoreCache) { scoreCache[key] = p }
        return p
    }

    /**
     * P3-5：缓存键 = 模型代数 + pkg + 文本 + 渠道的 64 位 FNV-1a 组合（零字符串分配）。
     * pkg/channelKey 必须与文本一起进哈希，否则不同 App/渠道的同文本通知互相命中
     * （渠道偏置判错）。碰撞概率 ~2^-64 可忽略，属正确性取舍（代码级取舍已在此注明）。
     */
    private fun cacheKey(pkg: String, normalizedText: String, channelKey: Int?): Long {
        var h = modelEpoch xor -3750763034362895579L // FNV-1a 64 offset basis
        val prime = 0x100000001B3L
        for (c in pkg) {
            h = h xor c.code.toLong()
            h *= prime
        }
        h = h xor 0x1FL
        h *= prime
        for (c in normalizedText) {
            h = h xor c.code.toLong()
            h *= prime
        }
        h = h xor 0x1FL
        h *= prime
        h = h xor (channelKey ?: 0).toLong()
        h *= prime
        return h
    }

    /** effective 更新统一走此函数（同步刷新打分缓存代数） */
    private fun setEffective(model: SpamModel?) {
        effective = model
        modelEpoch++
    }

    /** 基线模型指纹（用于判断标注是否需要重新拟合）。 */
    suspend fun baseFingerprint(): Long = baseModel()?.fingerprint ?: 0L

    /** 上次拟合所基于的基线指纹；0 表示尚未拟合过。 */
    suspend fun tunedFingerprint(): Long = withContext(Dispatchers.IO) {
        if (fingerprintFile.exists()) fingerprintFile.readText().trim().toLongOrNull() ?: 0L else 0L
    }

    suspend fun setTunedFingerprint(fp: Long) = withContext(Dispatchers.IO) {
        fingerprintFile.parentFile?.mkdirs()
        fingerprintFile.writeText(fp.toString())
    }

    /**
     * 上次拟合所用的**学习 schema 版本**；0 表示尚未拟合过（含老版本遗留 delta）。
     *
     * 2.2.1 Dev 1 引入：`SpamTuner.fit` 的语义变了（混杂渠道不建桶 / 渠道桶幅值封顶 /
     * 重复学习不放大渠道桶）。落盘的 `spam_delta.bin` 是**只增不改的历史产物**——
     * 老用户文件里可能带着被旧语义灌歪的渠道桶，若不做迁移，本次修复对「已经学过、
     * 但从此不再点学习」的用户完全不起作用（表现依旧是「学为正常也没用」）。
     */
    suspend fun learningSchema(): Int = withContext(Dispatchers.IO) {
        if (schemaFile.exists()) schemaFile.readText().trim().toIntOrNull() ?: 0 else 0
    }

    suspend fun setLearningSchema(v: Int) = withContext(Dispatchers.IO) {
        schemaFile.parentFile?.mkdirs()
        schemaFile.writeText(v.toString())
    }

    /**
     * 是否需要重新拟合（标注非空时由调用方保证）。
     *
     * P0-6：除 schema 外**还要比对基线指纹**——旧实现只查 schema，换 base（如 vc88 的
     * v9→v12）而 schema 未变时返回 false，启动迁移不触发，已学习用户带着"旧基线上拟合的
     * 残差"跑新基线，直到打开历史页才被 HistoryViewModel 的指纹比对纠正。CHANGELOG 承诺的
     * "无需任何操作自动重算"必须靠这里的启动路径兑现。
     */
    suspend fun needsRefit(): Boolean =
        learningSchema() != LEARNING_SCHEMA || tunedFingerprint() != baseFingerprint()

    /** 应用新的学习修正：写入 delta 文件并重建生效模型。 */
    suspend fun applyDelta(delta: SpamDelta) = mutex.withLock {
        withContext(Dispatchers.IO) {
            deltaFile.parentFile?.mkdirs()
            if (delta.isEmpty) {
                deltaFile.delete()
            } else {
                // Dev 10（P1-3）：tmp + fsync + rename 原子替换。
                // 旧实现 `writeBytes` 直接截断重写：写入中途进程被杀/磁盘满 → 文件损坏，
                // 下次启动 readDelta() 解码失败被 get() 静默吞掉 → **用户全部学习成果无提示丢失**。
                val tmp = File(deltaFile.parentFile, deltaFile.name + ".tmp")
                try {
                    val bytes = delta.encode()
                    java.io.FileOutputStream(tmp).use { out ->
                        out.write(bytes)
                        out.fd.sync() // rename 前强制落盘，避免"rename 成功但内容未落盘"
                    }
                    if (!tmp.renameTo(deltaFile)) {
                        cc.ytdttj.noticleaner.diagnostics.RingLog.log(
                            cc.ytdttj.noticleaner.diagnostics.LogModules.MODEL,
                            "✗ delta 原子替换失败（rename 返回 false），本次学习未落盘",
                        )
                    }
                } finally {
                    if (tmp.exists()) tmp.delete()
                }
            }
        }
        val b = loadBase() ?: return@withLock
        setEffective(b.withDelta(delta))
        deltaVersion.value = System.currentTimeMillis()
        // Dev 9：模型变更落环形日志（诊断导出 MODEL 模块覆盖 24 小时——
        // 排查"升级后误杀"时，delta 何时被重写是关键时间锚点）
        cc.ytdttj.noticleaner.diagnostics.RingLog.log(
            cc.ytdttj.noticleaner.diagnostics.LogModules.MODEL,
            "应用学习 delta：${delta.indices.size} 项修正（epoch=${modelEpoch}）",
        )
    }

    /** 重置：删除学习修正，回到内置基线（标注由调用方决定是否清空）。 */
    suspend fun resetToBaseline() = mutex.withLock {
        withContext(Dispatchers.IO) {
            deltaFile.delete()
            fingerprintFile.delete()
            // 清理 1.0.7 之前版本的遗留文件
            File(context.filesDir, "model/model.bin").delete()
            File(context.filesDir, "model/learn_count").delete()
        }
        setEffective(loadBase())
        deltaVersion.value = 0L
        cc.ytdttj.noticleaner.diagnostics.RingLog.log(
            cc.ytdttj.noticleaner.diagnostics.LogModules.MODEL, "重置为内置基线（delta/指纹已删除）",
        )
    }

    /**
     * 推送给 LSPosed 模块的 delta 二进制快照（Dev 10，P1-3）。
     *
     * 必须在 [mutex] 内读取——旧实现里 ModuleConfigSync.pushDelta 用独立锁直接
     * `deltaFile.readBytes()`，与 [applyDelta] 的写入锁不共享，理论上能读到撕裂文件。
     * 现在读写统一收拢到本仓库的单一锁域；文件不存在返回 null。
     */
    suspend fun deltaSnapshot(): ByteArray? = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!deltaFile.exists()) null else runCatching { deltaFile.readBytes() }.getOrNull()
        }
    }

    private fun readDelta(): SpamDelta? {
        if (!deltaFile.exists()) return null
        return deltaFile.inputStream().use { SpamDelta.decode(it) }
    }

    /**
     * 1.3.2（P1-4）：模型加载挪 IO 线程——冷启动首条通知不再让实时槽等待 0.5MB 读取 + CRC32。
     * 1.3.2（P3-7②）：model.bin 单通道打包（src/main/resources），APP 端与模块端统一走 classLoader。
     */
    private suspend fun loadBase(): SpamModel? = withContext(Dispatchers.IO) {
        // P1-11：加载失败要缓存——旧实现每次调用都重读 resources + 解码 0.5MB + CRC + 写
        // 环形日志，而调用方（get/applyDelta/needsRefit）在每条通知路径上都会进来；
        // model.bin 损坏时等于每条通知重跑一遍失败。有限重试（3 次）兜底"临时 IO 抖动"，
        // 超过后进程内不再重试（重装/升级 = 新进程，自然恢复）。
        if (baseLoadFailedCount >= BASE_LOAD_RETRY_MAX) return@withContext null
        runCatching {
            base ?: SpamModel::class.java.classLoader
                ?.getResourceAsStream("model/model.bin")
                ?.use { SpamModel.load(it) }
                ?.also { base = it }
        }.onSuccess { m ->
            if (m != null) {
                baseLoadFailedCount = 0
                cc.ytdttj.noticleaner.diagnostics.RingLog.log(
                    cc.ytdttj.noticleaner.diagnostics.LogModules.MODEL,
                    "内置模型加载成功 fingerprint=${m.fingerprint}",
                )
            }
        }.onFailure {
            baseLoadFailedCount++
            android.util.Log.e("ModelRepository", "内置模型加载失败", it)
            cc.ytdttj.noticleaner.diagnostics.RingLog.log(
                cc.ytdttj.noticleaner.diagnostics.LogModules.MODEL,
                "✗ 内置模型加载失败(${baseLoadFailedCount}/$BASE_LOAD_RETRY_MAX): ${it.message}" +
                    "（打分将全部按 0.0 放行）",
            )
        }.getOrNull()
    }

    companion object {
        /** 打分 LRU 上限（ImprovePlan P1-3） */
        private const val SCORE_CACHE_MAX = 64

        /** P1-11：base 加载失败重试上限（超过后进程内短路） */
        private const val BASE_LOAD_RETRY_MAX = 3

        /**
         * 学习 schema 版本（2.2.1 Dev 1）。
         *
         * **每次改动 `SpamTuner.fit` 的拟合语义都必须 +1**，否则老用户已落盘的 delta
         * 不会重算，修复等于没发布。历史：
         * - 1：1.1.11 引入通道桶 / 样本权重
         * - 2：2.2.1 Dev 1 渠道桶护栏（混杂渠道不建桶 + 幅值封顶 + 重复学习不放大渠道桶）
         * - 3：2.2.1 vc86 纯渠道解除封顶（同侧 ≥3 条标注的渠道放宽到 ±8）
         * - 4：2.2.1 vc87 App 级先验桶（纯 App 桶接管渠道桶，三态互斥）
         */
        const val LEARNING_SCHEMA = 4
    }
}
