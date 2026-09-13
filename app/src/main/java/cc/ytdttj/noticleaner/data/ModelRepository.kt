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

    /** 内置基线模型（只读）；assets 缺失或损坏时为 null。 */
    suspend fun baseModel(): SpamModel? = mutex.withLock { loadBase() }

    /** 当前生效模型（base + 学习修正）；不可用时为 null。 */
    suspend fun get(): SpamModel? = mutex.withLock {
        effective ?: run {
            val b = loadBase() ?: return@run null
            val d = runCatching { readDelta() }.getOrNull()
            (if (d != null) b.withDelta(d) else b).also { effective = it }
        }
    }

    fun getBlocking(): SpamModel? = kotlinx.coroutines.runBlocking { get() }

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
        effective = b.withDelta(delta)
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
        effective = loadBase()
    }

    private fun readDelta(): SpamDelta? {
        if (!deltaFile.exists()) return null
        return deltaFile.inputStream().use { SpamDelta.decode(it) }
    }

    private fun loadBase(): SpamModel? = runCatching {
        base ?: context.assets.open("model/model.bin").use { SpamModel.load(it) }.also { base = it }
    }.onFailure {
        android.util.Log.e("ModelRepository", "内置模型加载失败", it)
    }.getOrNull()
}
