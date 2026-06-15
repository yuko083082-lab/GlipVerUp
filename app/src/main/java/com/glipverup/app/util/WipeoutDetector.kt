package com.glipverup.app.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log

object WipeoutDetector {

    // 💡 Service側でPixelCopyの切り抜き範囲を指定するために公開（public）に変更
    const val ROI_LEFT_PCT = 0.0f
    const val ROI_RIGHT_PCT = 1.0f
    const val ROI_TOP_PCT = 0.35f
    const val ROI_BOTTOM_PCT = 0.70f

    private const val CHAR_HEIGHT = 16
    
    // 💡 探索範囲の定数（隣の文字を拾わないよう ±3px に制限）
    private const val SCAN_RANGE = 6

    // 💡 スケーリング計算の基準となるロゴ全幅
    private const val REFERENCE_WIDTH = 102

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
    // 💡 102px基準の理想的な中心X座標（連続したロゴに最適化）

    // W: 11px(中心), 15px(幅)
    private val TEMPLATE_W = buildTemplate("W", 15, 11, """
        000000000000000
        100011110001111
        110011111001111
        110111111011111
        110111111011110
        111111111111110
        111111111111100
        111110111111100
        111110111111100
        111100111111000
        111100111111000
        111000111110000
        000000000000000
        000000000000000
        000000000000000
        000000000000000
    """)

    // I: 25px(中心), 6px(幅)
    private val TEMPLATE_I = buildTemplate("I", 6, 25, """
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
        000000
        000000
        000000
        000000
    """)

    // P: 37px(中心), 12px(幅)
    private val TEMPLATE_P = buildTemplate("P", 12, 37, """
        000000000000
        001111111110
        001111111111
        001111111111
        011111011111
        011111011111
        111111111111
        111111111110
        111111111100
        111100000000
        111100000000
        111100000000
        000000000000
        000000000000
        000000000000
        000000000000
    """)

    // E: 51px(中心), 10px(幅)
    private val TEMPLATE_E = buildTemplate("E", 10, 51, """
        0000000000
        0111111111
        0111111111
        0111111111
        0111110000
        0111111110
        0111111110
        0111111110
        1111100000
        1111111110
        1111111110
        1111111110
        0000000000
        0000000000
        0000000000
        0000000000
    """)

    // O: 65px(中心), 11px(幅)
    private val TEMPLATE_O = buildTemplate("O", 11, 65, """
        00000000000
        00111111111
        01111111111
        11111111111
        11111101111
        11111011111
        11111011111
        11111011111
        11111011111
        11111111111
        11111111110
        01111111100
        00000000000
        00000000000
        00000000000
        00000000000
    """)

    // U: 80px(中心), 12px(幅)
    private val TEMPLATE_U = buildTemplate("U", 12, 80, """
        000000000000
        011111011111
        011111011111
        011111011111
        011111011111
        011110111111
        011110111111
        011110111110
        111110111110
        111111111110
        111111111110
        011111111100
        000000000000
        000000000000
        000000000000
        000000000000
    """)

    // T: 94px(中心), 9px(幅)
    private val TEMPLATE_T = buildTemplate("T", 9, 94, """
        000000000
        111111111
        111111111
        111111111
        000111110
        000111110
        000111110
        000111110
        001111100
        001111100
        001111100
        001111100
        000000000
        000000000
        000000000
        000000000
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

    private var reusableScaledBitmap: Bitmap? = null
    private var reusableBinarizedBitmap: Bitmap? = null
    private var pixelArray: IntArray? = null
    private val canvas = Canvas()

    fun detectWipeout(roiBitmap: Bitmap?): DetectionResult {
        if (roiBitmap == null || roiBitmap.height == 0) return DetectionResult(false, emptyList(), emptyList(), emptyList())

        try {
            // 💡 高さ 16px に合わせてアスペクト比を維持してスケーリング
            val scaledWidth = (roiBitmap.width * CHAR_HEIGHT) / roiBitmap.height
            
            // Bitmap再利用ロジック
            if (reusableScaledBitmap == null || reusableScaledBitmap!!.width != scaledWidth) {
                reusableScaledBitmap?.recycle()
                reusableScaledBitmap = Bitmap.createBitmap(scaledWidth, CHAR_HEIGHT, Bitmap.Config.ARGB_8888)
            }
            val scaledBitmap = reusableScaledBitmap!!
            canvas.setBitmap(scaledBitmap)
            canvas.drawBitmap(roiBitmap, null, android.graphics.Rect(0, 0, scaledWidth, CHAR_HEIGHT), null)

            // 💡 スケーリング後の画像を二値化してデバッグ用に保持
            if (reusableBinarizedBitmap == null || reusableBinarizedBitmap!!.width != scaledWidth) {
                reusableBinarizedBitmap?.recycle()
                reusableBinarizedBitmap = Bitmap.createBitmap(scaledWidth, CHAR_HEIGHT, Bitmap.Config.ARGB_8888)
                pixelArray = IntArray(scaledWidth * CHAR_HEIGHT)
            }
            val binarized = reusableBinarizedBitmap!!
            val pixels = pixelArray!!

            // getPixelsによる高速一括取得
            scaledBitmap.getPixels(pixels, 0, scaledWidth, 0, 0, scaledWidth, CHAR_HEIGHT)

            for (i in pixels.indices) {
                val color = pixels[i]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                val isBright = (r > 160 && g > 140 && b < 140)
                pixels[i] = if (isBright) Color.WHITE else Color.BLACK
            }
            binarized.setPixels(pixels, 0, scaledWidth, 0, 0, scaledWidth, CHAR_HEIGHT)

            val charScores = FloatArray(ALL_TEMPLATES.size)
            val charBestX = IntArray(ALL_TEMPLATES.size)

            // 💡 102px基準の理想位置から、個別に探索範囲を絞って精密スキャン
            // 💡 [改善] 文字が重ならないよう、直前の文字の確定位置を基準に探索を開始する
            var lastBestX = -1

            for (i in ALL_TEMPLATES.indices) {
                val template = ALL_TEMPLATES[i]
                
                // REFERENCE_WIDTH基準での「文字の左端」を算出
                val idealLeftRef = template.idealCenterX - (template.width / 2)
                
                // 実際のscaledWidthに合わせた開始位置を計算
                var startX = ((idealLeftRef * scaledWidth) / REFERENCE_WIDTH) - (SCAN_RANGE / 2)
                
                // 💡 [順序制約] 直前の文字の左端よりは必ず右側から探し始める
                // これにより、E, O, U などが同じ明るい塊に吸着して重なるのを防ぐ
                if (startX <= lastBestX) {
                    startX = lastBestX + 1
                }

                var bestScoreForThisChar = 0f
                var bestXForThisChar = startX
                
                // 💡 【重要】必ずSCAN_RANGE分をフルスキャンして最高スコアを探す
                for (offset in 0..SCAN_RANGE) {
                    val currentX = startX + offset
                    // 💡 【改善】マイナスの間はスルー（右側にスキャン範囲がずれるのを防ぐ）
                    if (currentX < 0) continue
                    if (currentX + template.width > scaledWidth) break
                    
                    val matchRatio = checkCharMatch(binarized, template, currentX)
                    
                    if (matchRatio > bestScoreForThisChar) {
                        bestScoreForThisChar = matchRatio
                        bestXForThisChar = currentX
                    }
                }
                charScores[i] = bestScoreForThisChar
                charBestX[i] = bestXForThisChar
                lastBestX = bestXForThisChar
            }

            // 💡 FIFOバッファ側のロジックで判定するため、ここでは個別の「合格判定」のみを計算
            // (Service側のスコア蓄計と整合性を取るため、最終判定はServiceに任せる)
            val matchedChars = ALL_TEMPLATES.indices.filter { charScores[it] >= CHAR_MATCH_THRESHOLD }.map { ALL_TEMPLATES[it].charName }
            val scoresList = charScores.toList()
            val xOffsets = charBestX.toList()

            // scaledBitmapは再利用フィールドなのでrecycleしない
            // binarizedはDetectionResultで外部（Service等）に渡され、そこで保存に使用される可能性があるため、
            // ここではコピーを渡すか、あるいはライフサイクル管理を慎重に行う必要がある。
            // 呼び出し元のDetectionControllerで適切に処理されていることを前提に、ここではコピーを作成して返す。
            val resultBinarized = Bitmap.createBitmap(binarized)
            
            return DetectionResult(false, matchedChars, scoresList, xOffsets, resultBinarized)

        } catch (e: Exception) {
            Log.e("WipeoutDetector", "Detection error", e)
            return DetectionResult(false, emptyList(), emptyList(), emptyList())
        }
    }

    /**
     * 💡 スコアリスト（各文字の最大値）を受け取り、トリプルチェック判定を行う
     */
    fun evaluateTripleCheck(scores: List<Float>): Boolean {
        if (scores.size < ALL_TEMPLATES.size) return false
        
        val countOver80 = scores.count { it >= 0.80f }
        val countOver75 = scores.count { it >= 0.75f }
        val minScore = scores.minOrNull() ?: 0f

        // 条件1: 80%以上が2文字以上
        // 条件2: 75%以上が3文字以上
        // 条件3: 全7文字が60%以上
        return (countOver80 >= 2) && (countOver75 >= 3) && (minScore >= 0.60f)
    }

    private fun checkCharMatch(binarized: Bitmap, template: CharTemplate, startX: Int): Float {
        var matchOneCount = 0
        var totalOneCount = 0
        var matchZeroCount = 0
        var totalZeroCount = 0

        val width = template.width
        val height = CHAR_HEIGHT
        val pixels = IntArray(width * height)
        // 指定範囲のピクセルを一括取得
        binarized.getPixels(pixels, 0, width, startX, 0, width, height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixelColor = pixels[y * width + x]
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
