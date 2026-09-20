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

    @Volatile
    private var base: SpamModel? = null

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
        val p = model.scoreNormalized(normalizedText, channelKey)
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

    /** 应用新的学习修正：写入 delta 文件并重建生效模型。 */
    suspend fun applyDelta(delta: SpamDelta) = mutex.withLock {
        withContext(Dispatchers.IO) {
            deltaFile.parentFile?.mkdirs()
            if (delta.isEmpty) deltaFile.delete() else deltaFile.writeBytes(delta.encode())
        }
        val b = loadBase() ?: return@withLock
        setEffective(b.withDelta(delta))
        deltaVersion.value = System.currentTimeMillis()
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
        runCatching {
            base ?: SpamModel::class.java.classLoader
                ?.getResourceAsStream("model/model.bin")
                ?.use { SpamModel.load(it) }
                ?.also { base = it }
        }.onFailure {
            android.util.Log.e("ModelRepository", "内置模型加载失败", it)
        }.getOrNull()
    }

    companion object {
        /** 打分 LRU 上限（ImprovePlan P1-3） */
        private const val SCORE_CACHE_MAX = 64
    }
}
