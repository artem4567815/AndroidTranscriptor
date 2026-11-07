package hackuna.matata.ml.diarization

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Группировка участков речи по отпечаткам голоса (чистая логика, без JNI — покрыта тестами).
 *
 *  1. Отпечатки нормализуются; близость — косинус.
 *  2. Агломеративная кластеризация со средним связыванием: начинаем с «каждый участок — свой спикер»
 *     и сливаем две самые похожие группы, пока не останется одна; запоминаем разбиения на 1..maxSpeakers.
 *  3. Число спикеров: задано пользователем — берём его; «Авто» — то разбиение, где группы лучше всего
 *     отделены друг от друга (средний «силуэт»); если даже лучший силуэт слабый — один спикер.
 *     Затем группы, чьи центры похожи сильнее [sameSpeakerSimilarity], сливаются: это один человек
 *     (силуэт не зависит от масштаба и может «разделить» даже почти одинаковые голоса).
 *  4. Участки сверх [maxUnits] (длинные записи) и участки без отпечатка относятся к ближайшей группе.
 */
object SpeakerClustering {

    /**
     * @param embeddings отпечаток каждого участка или null, если его не удалось посчитать
     * @param numSpeakers > 0 — ровно столько спикеров; 0 — определить автоматически
     * @return номер спикера (с нуля, по первому появлению) для каждого участка
     */
    fun cluster(
        embeddings: List<FloatArray?>,
        numSpeakers: Int,
        minSilhouette: Float = 0.10f,
        maxSpeakers: Int = 6,
        maxUnits: Int = 400,
        sameSpeakerSimilarity: Double = SAME_SPEAKER_SIMILARITY,
    ): IntArray {
        val n = embeddings.size
        if (n == 0) return IntArray(0)
        val valid = embeddings.indices.filter { embeddings[it] != null }
        if (valid.isEmpty()) return IntArray(n)
        val vec = embeddings.map { it?.let(::normalize) }

        val core = if (valid.size <= maxUnits) valid else List(maxUnits) { valid[it * valid.size / maxUnits] }
        val m = core.size
        val sim = Array(m) { i -> DoubleArray(m) { j -> dot(vec[core[i]]!!, vec[core[j]]!!) } }
        val kCap = max(maxSpeakers, numSpeakers).coerceAtMost(m)
        val partitions = agglomerate(sim, kCap)

        val k = when {
            m == 1 -> 1
            numSpeakers > 0 -> numSpeakers.coerceAtMost(m)
            else -> chooseK(sim, partitions, minSilhouette, maxSpeakers)
        }
        var coreLabels = partitions.getValue(k)
        if (numSpeakers <= 0) coreLabels = mergeSimilar(coreLabels, core.map { vec[it]!! }, sameSpeakerSimilarity)
        val kFinal = coreLabels.max() + 1

        // Центры групп и отнесение остальных участков к ближайшему центру.
        val dim = vec[core[0]]!!.size
        val centers = Array(kFinal) { FloatArray(dim) }
        core.forEachIndexed { i, idx -> val c = centers[coreLabels[i]]; val v = vec[idx]!!; for (d in 0 until dim) c[d] += v[d] }
        val normCenters = centers.map(::normalize)
        val labels = IntArray(n) { -1 }
        core.forEachIndexed { i, idx -> labels[idx] = coreLabels[i] }
        for (idx in valid) if (labels[idx] < 0) labels[idx] = normCenters.indices.maxBy { dot(normCenters[it], vec[idx]!!) }
        // Участки без отпечатка — метка ближайшего по порядку участка с отпечатком.
        for (idx in 0 until n) if (labels[idx] < 0) labels[idx] = labels[valid.minBy { kotlin.math.abs(it - idx) }]
        return renumber(labels)
    }

    /**
     * Центры групп разных людей на наших записях похожи на 0.44–0.64 (CAM++ zh-cn, docs/TESTS.md),
     * одного человека — около 0.8–0.9. Порог с запасом.
     */
    const val SAME_SPEAKER_SIMILARITY = 0.80

    /** Сливает группы, центры которых похожи сильнее [threshold]; метки перенумеровываются. */
    internal fun mergeSimilar(labels: IntArray, vectors: List<FloatArray>, threshold: Double): IntArray {
        var cur = labels.copyOf()
        while (true) {
            val groups = cur.distinct()
            if (groups.size < 2) return renumber(cur)
            val centers = groups.associateWith { g ->
                val c = FloatArray(vectors[0].size)
                cur.indices.filter { cur[it] == g }.forEach { i -> for (d in c.indices) c[d] += vectors[i][d] }
                normalize(c)
            }
            var best = threshold
            var pair: Pair<Int, Int>? = null
            for (i in groups.indices) for (j in i + 1 until groups.size) {
                val sim = dot(centers.getValue(groups[i]), centers.getValue(groups[j]))
                if (sim > best) { best = sim; pair = groups[i] to groups[j] }
            }
            val (keep, drop) = pair ?: return renumber(cur)
            cur = IntArray(cur.size) { if (cur[it] == drop) keep else cur[it] }
        }
    }

    /** Разбиения на k = 1..maxK групп (k → метка каждого элемента). */
    internal fun agglomerate(sim: Array<DoubleArray>, maxK: Int): Map<Int, IntArray> {
        val m = sim.size
        val s = Array(m) { sim[it].copyOf() }
        val size = IntArray(m) { 1 }
        val active = BooleanArray(m) { true }
        val owner = IntArray(m) { it }
        val result = HashMap<Int, IntArray>()
        var count = m
        if (count <= maxK) result[count] = snapshot(owner)
        while (count > 1) {
            var bestA = -1
            var bestB = -1
            var best = Double.NEGATIVE_INFINITY
            for (a in 0 until m) {
                if (!active[a]) continue
                for (b in a + 1 until m) if (active[b] && s[a][b] > best) { best = s[a][b]; bestA = a; bestB = b }
            }
            for (c in 0 until m) {
                if (!active[c] || c == bestA || c == bestB) continue
                val v = (s[bestA][c] * size[bestA] + s[bestB][c] * size[bestB]) / (size[bestA] + size[bestB])
                s[bestA][c] = v
                s[c][bestA] = v
            }
            size[bestA] += size[bestB]
            active[bestB] = false
            for (i in 0 until m) if (owner[i] == bestB) owner[i] = bestA
            count--
            if (count <= maxK) result[count] = snapshot(owner)
        }
        return result
    }

    internal fun chooseK(sim: Array<DoubleArray>, partitions: Map<Int, IntArray>, minSilhouette: Float, maxK: Int): Int {
        val m = sim.size
        if (m < 3) return 1
        var bestK = 1
        var best = Double.NEGATIVE_INFINITY
        for (k in 2..minOf(maxK, m - 1)) {
            val labels = partitions[k] ?: continue
            val sil = silhouette(sim, labels)
            if (sil > best) { best = sil; bestK = k }
        }
        return if (best < minSilhouette) 1 else bestK
    }

    /** Средний силуэт: насколько каждый элемент ближе к своей группе, чем к ближайшей чужой (−1..1). */
    internal fun silhouette(sim: Array<DoubleArray>, labels: IntArray): Double {
        val groups = labels.distinct()
        if (groups.size < 2) return -1.0
        var total = 0.0
        for (i in labels.indices) {
            val own = labels.indices.filter { it != i && labels[it] == labels[i] }
            if (own.isEmpty()) continue // одиночка — силуэт 0
            val a = own.sumOf { 1 - sim[i][it] } / own.size
            val b = groups.filter { it != labels[i] }.minOf { g ->
                val other = labels.indices.filter { labels[it] == g }
                other.sumOf { 1 - sim[i][it] } / other.size
            }
            total += (b - a) / max(a, b)
        }
        return total / labels.size
    }

    private fun snapshot(owner: IntArray): IntArray {
        val ids = HashMap<Int, Int>()
        return IntArray(owner.size) { ids.getOrPut(owner[it]) { ids.size } }
    }

    private fun renumber(labels: IntArray): IntArray {
        val ids = HashMap<Int, Int>()
        return IntArray(labels.size) { ids.getOrPut(labels[it]) { ids.size } }
    }

    private fun normalize(v: FloatArray): FloatArray {
        var n = 0.0
        for (x in v) n += x * x.toDouble()
        val norm = sqrt(n).toFloat().takeIf { it > 1e-9f } ?: return v.copyOf()
        return FloatArray(v.size) { v[it] / norm }
    }

    private fun dot(a: FloatArray, b: FloatArray): Double {
        var s = 0.0
        for (i in a.indices) s += a[i] * b[i].toDouble()
        return s
    }
}
