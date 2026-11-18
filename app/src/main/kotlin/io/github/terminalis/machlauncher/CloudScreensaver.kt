package io.github.terminalis.machlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.service.dreams.DreamService
import android.text.format.DateFormat
import android.view.View
import java.util.Calendar
import java.util.Date
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SCRIM = 0x59000000
private const val GLASS_TINT = 0x80000000.toInt()
private const val GLASS_SHADOW = 0x4D000000
private const val DATE_COLOR = 0xD9FFFFFF.toInt()
private const val LOW_BASELINE = 0.62f
private const val HIGH_BASELINE = 0.28f

class CloudScreensaver : DreamService() {
    private val scope = MainScope()
    private var clock: Job? = null
    private var shownAsset: String? = null
    private lateinit var view: CloudClockView
    private lateinit var baker: GlassBaker
    private lateinit var settings: SettingsService

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        val metrics = resources.displayMetrics
        baker = GlassBaker(metrics.widthPixels, metrics.heightPixels, metrics.density)
        settings = SettingsService(getSharedPreferences("mach", Context.MODE_PRIVATE))
        view = CloudClockView(
            this, metrics.density, metrics.widthPixels, metrics.heightPixels,
            Typeface.createFromAsset(assets, "fonts/Inter-ExtraBold.ttf"),
            Typeface.createFromAsset(assets, "fonts/Inter-Medium.ttf"),
        )
        setContentView(view)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        clock = scope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                show(now)
                delay(millisToNextMinute(now))
            }
        }
    }

    override fun onDreamingStopped() {
        clock?.cancel()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        scope.cancel()
        super.onDetachedFromWindow()
    }

    private suspend fun show(now: Long) {
        val calendar = Calendar.getInstance().apply { timeInMillis = now }
        val minutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        val lightFrom = settings.lightFromMinutes
        val darkFrom = settings.darkFromMinutes
        val asset = if (showsDayClouds(settings.backgroundChoice, minutes, lightFrom, darkFrom)) CLOUDS_BY_DAY else CLOUDS_BY_NIGHT
        if (asset != shownAsset) {
            val density = resources.displayMetrics.density
            val clouds = withContext(Dispatchers.Default) {
                val source = assets.open(bundledVariant(asset, density)).use { BitmapFactory.decodeStream(it) }
                val cover = baker.screenCover(source)
                view.dimmed(cover) to baker.glassFrom(cover)
            }
            shownAsset = asset
            view.setClouds(clouds.first, clouds.second)
        }
        val time = Date(now)
        val clock = statusClock(time, DateFormat.is24HourFormat(this))
        val position = skyPosition(minutes, lightFrom, darkFrom)
        view.setClock(withContext(Dispatchers.Default) { view.bakeClock(clock, statusDate(time), position) })
    }
}

class BakedClock(val bitmap: Bitmap, val left: Int, val top: Int)

class CloudClockView(
    context: Context,
    private val d: Float,
    private val screenWidth: Int,
    private val screenHeight: Int,
    extraBold: Typeface,
    medium: Typeface,
) : View(context) {
    private val radius = 24 * d
    private val spread = 2 * d
    private val pad = ceil(blurPx(20f, d) * 1.8f + spread).toInt()
    private val tilePadX = 32 * d
    private val tilePadY = 22 * d
    private val margin = 32 * d
    private val dateGap = 14 * d
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val scrimPaint = Paint().apply { color = SCRIM }
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = extraBold
        textSize = 88 * d
        letterSpacing = -0.02f
        color = Color.WHITE
    }
    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = medium
        textSize = 20 * d
        color = DATE_COLOR
        textAlign = Paint.Align.CENTER
        setShadowLayer(4 * d, 0f, d, 0x80000000.toInt())
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GLASS_SHADOW
        maskFilter = BlurMaskFilter(blurPx(20f, d), BlurMaskFilter.Blur.NORMAL)
    }
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GLASS_TINT }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = d
    }
    private val glassMatrix = Matrix()
    private val capHeight = Rect().also { timePaint.getTextBounds("0", 0, 1, it) }.height()
    private var clouds: Bitmap? = null
    private var glass: Bitmap? = null
    private var clock: BakedClock? = null

    fun dimmed(cover: Bitmap): Bitmap {
        val dimmed = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(dimmed)
        canvas.drawBitmap(cover, 0f, 0f, null)
        canvas.drawRect(0f, 0f, screenWidth.toFloat(), screenHeight.toFloat(), scrimPaint)
        dimmed.prepareToDraw()
        return dimmed
    }

    fun setClouds(clouds: Bitmap, glass: Bitmap) {
        this.clouds = clouds
        this.glass = glass
        clock = null
    }

    fun bakeClock(time: String, date: String, position: SkyPosition): BakedClock? {
        val glass = glass ?: return null
        val timeWidth = timePaint.measureText(time)
        val tileWidth = timeWidth + 2 * tilePadX
        val tileHeight = capHeight + 2 * tilePadY
        val width = ceil(max(tileWidth, datePaint.measureText(date))).toInt() + 2 * pad
        val height = ceil(tileHeight + dateGap + datePaint.textSize).toInt() + 2 * pad
        val baseline = screenHeight * (LOW_BASELINE + (HIGH_BASELINE - LOW_BASELINE) * position.height)
        val left = (margin + (screenWidth - 2 * margin - width) * position.across).roundToInt()
        val top = (baseline - capHeight - tilePadY - pad).roundToInt()
        val tileLeft = (width - tileWidth) / 2f
        val tileTop = pad.toFloat()
        val tileRight = tileLeft + tileWidth
        val tileBottom = tileTop + tileHeight

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawRoundRect(tileLeft - spread, tileTop - spread, tileRight + spread, tileBottom + spread, radius + spread, radius + spread, shadowPaint)
        glassMatrix.setScale(screenWidth.toFloat() / glass.width, screenHeight.toFloat() / glass.height)
        glassMatrix.postTranslate(-left.toFloat(), -top.toFloat())
        glassPaint.shader = BitmapShader(glass, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(glassMatrix) }
        canvas.drawRoundRect(tileLeft, tileTop, tileRight, tileBottom, radius, radius, glassPaint)
        canvas.drawRoundRect(tileLeft, tileTop, tileRight, tileBottom, radius, radius, tintPaint)
        outlinePaint.shader = LinearGradient(
            0f, tileTop, 0f, tileBottom,
            intArrayOf(0x40FFFFFF, 0x1AFFFFFF, 0x0AFFFFFF),
            floatArrayOf(0f, 0.4f, 1f),
            Shader.TileMode.CLAMP,
        )
        val inset = 0.5f * d
        canvas.drawRoundRect(tileLeft + inset, tileTop + inset, tileRight - inset, tileBottom - inset, radius - inset, radius - inset, outlinePaint)
        canvas.drawText(time, tileLeft + tilePadX, tileTop + tilePadY + capHeight, timePaint)
        canvas.drawText(date, width / 2f, tileBottom + dateGap + datePaint.textSize * 0.8f, datePaint)
        bitmap.prepareToDraw()
        return BakedClock(bitmap, left, top)
    }

    fun setClock(clock: BakedClock?) {
        this.clock = clock
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        clouds?.let { canvas.drawBitmap(it, 0f, 0f, bitmapPaint) }
        clock?.let { canvas.drawBitmap(it.bitmap, it.left.toFloat(), it.top.toFloat(), bitmapPaint) }
    }
}
