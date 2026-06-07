package com.glipverup.app.util

import android.graphics.Bitmap
import android.graphics.Color

object WipeoutDetector {

    // 💡 Service側でPixelCopyの切り抜き範囲を指定するために公開（public）に変更
    const val ROI_LEFT_PCT = 0.0f
    const val ROI_RIGHT_PCT = 1.0f
    const val ROI_TOP_PCT = 0.35f
    const val ROI_BOTTOM_PCT = 0.70f

    private const val CHAR_HEIGHT = 16

    // 【仕様】1文字あたりの合格ライン（70%）
    private const val CHAR_MATCH_THRESHOLD = 0.70f
    // 【仕様】デバッグ用：惜しい判定をログに残すしきい値
    private const val CANDIDATE_LOG_THRESHOLD = 0.50f
    // 【仕様】エフェクト被りを考慮し、2文字以上見つかればWIPEOUT確定とする
    private const val REQUIRED_MATCH_COUNT = 3

    // 💡 文字ごとの型紙データと探索基準位置を持つクラス
    data class CharTemplate(
        val charName: String,
        val width: Int,
        val idealCenterX: Int, // 102px基準の理想的な中心X座標
        val pixels: Array<IntArray>
    )

    /**
     * 文字列から可変幅の型紙配列を自動生成するヘルパー
     */
    private fun buildTemplate(name: String, width: Int, centerX: Int, pattern: String): CharTemplate {
        val pixels = pattern.trimIndent().lines()
            .filter { it.length >= width }
            .take(CHAR_HEIGHT)
            .map { line ->
                val row = IntArray(width)
                for (i in 0 until width) {
                    row[i] = if (line[i] == '1') 1 else 0
                }
                row
            }.toTypedArray()
        return CharTemplate(name, width, centerX, pixels)
    }

    // --- 文字幅と中心座標を個別に最適化した全7文字の型紙データ ---
    
    // W: 11px(中心), 23px(幅)
    private val TEMPLATE_W = buildTemplate("W", 20, 11, """
        00000000000000000000
        11110000111100001111
        11110001111100011111
        11110001111100011111
        11110011111100111111
        01110111111101111111
        01111111111111111110
        01111111111111111100
        01111111110111111100
        00111111100111111000
        00111111100111111000
        00011111000111110000
        00011111000111110000
        00000000000000000000
        00000000000000000000
        00000000000000000000
    """)

    // I: 30px(中心), 6px(幅)
    private val TEMPLATE_I = buildTemplate("I", 6, 30, """
        000000
        011111
        011111
        011111
        011111
        111110
        111110
        111110
        111110
        111110
        111100
        111100
        111100
        000000
        000000
        000000
    """)

    // P: 44px(中心), 16px(幅)
    private val TEMPLATE_P = buildTemplate("P", 14, 44, """
        00000000000000
        00111111111000
        00111111111110
        00111111111110
        01111100111110
        01111100111110
        11111111111100
        11111111111000
        11111111110000
        11111000000000
        11111000000000
        11111000000000
        11110000000000
        11110000000000
        00000000000000
        00000000000000
    """)

    // E: 56px(中心), 14px(幅)
    private val TEMPLATE_E = buildTemplate("E", 14, 56, """
        00000000000000
        00111111111111
        00111111111111
        01111111111111
        01111100000000
        01111100000000
        11111111111100
        11111111111000
        11111100000000
        11111100000000
        11111111111111
        11111111111111
        11111111111110
        00000000000000
        00000000000000
        00000000000000
    """)

    // O: 71px(中心), 14px(幅)
    private val TEMPLATE_O = buildTemplate("O", 14, 71, """
        00000000000000
        00111111111100
        01111111111110
        11111111111111
        11111110111111
        11111100111111
        11111100111111
        11111100111111
        11111100111111
        11111101111111
        11111111111111
        01111111111110
        00111111111100
        00000000000000
        00000000000000
        00000000000000
    """)

    // U: 87px(中心), 14px(幅)
    private val TEMPLATE_U = buildTemplate("U", 14, 87, """
        00000000000000
        01111110111111
        01111110111111
        01111110111111
        01111110111111
        11111101111110
        11111101111110
        11111101111110
        11111101111110
        11111101111110
        11111101111110
        01111101111100
        01111111111100
        00000000000000
        00000000000000
        00000000000000
    """)

    // T: 96px(中心), 15px(幅)
    private val TEMPLATE_T = buildTemplate("T", 14, 96, """
        11111111111111
        11111111111111
        11111111111111
        11111111111111
        00001111111000
        00001111111000
        00001111111000
        00001111110000
        00001111110000
        00001111110000
        00001111110000
        00011111100000
        00011111100000
        00000000000000
        00000000000000
        00000000000000
    """)

    private val ALL_TEMPLATES = arrayOf(
        TEMPLATE_W, TEMPLATE_I, TEMPLATE_P, TEMPLATE_E, TEMPLATE_O, TEMPLATE_U, TEMPLATE_T
    )

    data class DetectionResult(
        val isDetected: Boolean,
        val matchedChars: List<String>,
        val scores: List<Float>,
        val bestXOffsets: List<Int>,
        val binarizedBitmap: Bitmap? = null
    )

    fun detectWipeout(roiBitmap: Bitmap?): DetectionResult {
        if (roiBitmap == null || roiBitmap.height == 0) return DetectionResult(false, emptyList(), emptyList(), emptyList())

        try {
            // 💡 高さ 16px に合わせてアスペクト比を維持してスケーリング
            val scaledWidth = (roiBitmap.width * CHAR_HEIGHT) / roiBitmap.height
            val scaledBitmap = Bitmap.createScaledBitmap(roiBitmap, scaledWidth, CHAR_HEIGHT, true)

            // 💡 スケーリング後の画像を二値化してデバッグ用に保持
            val binarized = Bitmap.createBitmap(scaledWidth, CHAR_HEIGHT, Bitmap.Config.ARGB_8888)
            for (y in 0 until CHAR_HEIGHT) {
                for (x in 0 until scaledWidth) {
                    val color = scaledBitmap.getPixel(x, y)
                    val r = Color.red(color)
                    val g = Color.green(color)
                    val b = Color.blue(color)
                    val isBright = (r > 160 && g > 140 && b < 140)
                    binarized.setPixel(x, y, if (isBright) Color.WHITE else Color.BLACK)
                }
            }

            val charScores = FloatArray(ALL_TEMPLATES.size)
            val charBestX = IntArray(ALL_TEMPLATES.size)

            // 💡 102px基準の理想位置から、個別に探索範囲を絞って精密スキャン
            for (i in ALL_TEMPLATES.indices) {
                val template = ALL_TEMPLATES[i]
                
                // 102px基準での「文字の左端」を算出
                val idealLeft102 = template.idealCenterX - (template.width / 2)
                
                // 実際のscaledWidthに合わせた開始位置を計算し、バッファとして-10px
                val startX = ((idealLeft102 * scaledWidth) / 102) - 10
                
                var bestScoreForThisChar = 0f
                var bestXForThisChar = 0
                
                // 💡 【重要】必ず20px分をフルスキャンして最高スコアを探す
                // 💡 【修正】各文字は自身のテンプレートのみを、自身の担当範囲内だけで探す
                for (offset in 0..20) {
                    val currentX = startX + offset
                    if (currentX < 0 || currentX + template.width > scaledWidth) continue
                    
                    val matchRatio = checkCharMatch(binarized, template, currentX)
                    
                    if (matchRatio > bestScoreForThisChar) {
                        bestScoreForThisChar = matchRatio
                        bestXForThisChar = currentX
                    }
                }
                charScores[i] = bestScoreForThisChar
                charBestX[i] = bestXForThisChar
            }

            // --- 💡 多段判定ロジック (改良版トリプルチェック) ---
            val countOver80 = charScores.count { it >= 0.80f }
            val countOver75 = charScores.count { it >= 0.75f }
            val countOver70 = charScores.count { it >= 0.70f }
            val minScore = charScores.minOrNull() ?: 0f

            // 条件1: 80%以上が1文字以上 OR 75%以上が2文字以上
            // 条件2: 70%以上が3文字以上
            // 条件3: 全7文字が50%以上
            val isDetected = ((countOver80 >= 1) || (countOver75 >= 2)) && (countOver70 >= 3) && (minScore >= 0.50f)

            val matchedChars = ALL_TEMPLATES.indices.filter { charScores[it] >= CHAR_MATCH_THRESHOLD }.map { ALL_TEMPLATES[it].charName }
            val scoresList = charScores.toList()
            val xOffsets = charBestX.toList()

            if (!isDetected && (countOver70 >= 2 || countOver75 >= 1)) {
                // 惜しい時のデバッグログ
                val detail = ALL_TEMPLATES.indices.joinToString(", ") { "${ALL_TEMPLATES[it].charName}:${String.format(java.util.Locale.US, "%.2f", charScores[it])}" }
                android.util.Log.d("ZZZGlip_Detection", "Near miss (C1:$countOver75, C2:$countOver70, Min:$minScore): [$detail]")
            }

            scaledBitmap.recycle()
            return DetectionResult(isDetected, matchedChars, scoresList, xOffsets, binarized)

        } catch (e: Exception) {
            e.printStackTrace()
            return DetectionResult(false, emptyList(), emptyList(), emptyList())
        }
    }

    private fun checkCharMatch(binarized: Bitmap, template: CharTemplate, startX: Int): Float {
        var matchOneCount = 0
        var totalOneCount = 0
        var matchZeroCount = 0
        var totalZeroCount = 0

        for (y in 0 until CHAR_HEIGHT) {
            for (x in 0 until template.width) {
                if (startX + x >= binarized.width) continue

                val pixelColor = binarized.getPixel(startX + x, y)
                // 二値化済みなのでColor.WHITEかどうかだけで判定
                val actualValue = if (pixelColor == Color.WHITE) 1 else 0
                val templateValue = template.pixels[y][x]

                if (templateValue == 1) {
                    totalOneCount++
                    if (actualValue == 1) matchOneCount++
                } else {
                    totalZeroCount++
                    if (actualValue == 0) matchZeroCount++
                }
            }
        }

        val oneMatchRatio = if (totalOneCount > 0) matchOneCount.toFloat() / totalOneCount else 1.0f
        val zeroMatchRatio = if (totalZeroCount > 0) matchZeroCount.toFloat() / totalZeroCount else 1.0f

        // 文字部分が30%未満なら絶望的なので0
        if (oneMatchRatio < 0.30f) {
            return 0f
        }

        return (oneMatchRatio + zeroMatchRatio) / 2.0f
    }
    
    /**
     * デバッグ用：指定位置での差分マップを生成する
     */
    fun generateDiffMap(binarized: Bitmap, template: CharTemplate, startX: Int): Bitmap {
        val result = Bitmap.createBitmap(template.width, CHAR_HEIGHT, Bitmap.Config.ARGB_8888)
        for (y in 0 until CHAR_HEIGHT) {
            for (x in 0 until template.width) {
                val currentX = startX + x
                val actual = if (currentX < binarized.width && binarized.getPixel(currentX, y) == Color.WHITE) 1 else 0
                val expected = template.pixels[y][x]
                
                val color = when {
                    actual == 1 && expected == 1 -> Color.GREEN // 一致（文字）
                    actual == 0 && expected == 0 -> Color.BLACK // 一致（背景）
                    actual == 1 && expected == 0 -> Color.RED   // 余計（文字が出てる）
                    actual == 0 && expected == 1 -> Color.BLUE  // 不足（文字が欠けてる）
                    else -> Color.GRAY
                }
                result.setPixel(x, y, color)
            }
        }
        return result
    }
    
    fun getAllTemplates() = ALL_TEMPLATES
}
