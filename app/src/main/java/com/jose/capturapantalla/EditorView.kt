package com.jose.capturapantalla

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Lienzo del editor: muestra la captura, permite seleccionar una zona (recorte)
 * y dibujar anotaciones encima. Todo se guarda en coordenadas de la imagen,
 * así el resultado final tiene la resolución original.
 */
class EditorView(ctx: Context, private val imagen: Bitmap) : View(ctx) {

    enum class Herramienta { RECORTAR, LAPIZ, LINEA, FLECHA, RECTANGULO, MARCADOR, TEXTO }

    var herramienta = Herramienta.RECORTAR
        set(v) { field = v; invalidate() }
    var color = Color.RED

    /** Se llama al tocar con la herramienta Texto (coordenadas de imagen). */
    var alPedirTexto: ((Float, Float) -> Unit)? = null

    // ---------------------------------------------------------------- Modelo

    private sealed class Trazo(val color: Int, val ancho: Float)
    private class TrazoLibre(val path: Path, color: Int, ancho: Float, val marcador: Boolean) : Trazo(color, ancho)
    private class TrazoLinea(val x1: Float, val y1: Float, var x2: Float, var y2: Float,
                             color: Int, ancho: Float, val flecha: Boolean) : Trazo(color, ancho)
    private class TrazoRect(val x1: Float, val y1: Float, var x2: Float, var y2: Float,
                            color: Int, ancho: Float) : Trazo(color, ancho)
    private class TrazoTexto(val x: Float, val y: Float, val texto: String, color: Int, tam: Float) : Trazo(color, tam)

    private val trazos = mutableListOf<Trazo>()
    private var actual: Trazo? = null
    private var seleccion: RectF? = null
    private var anclaX = 0f
    private var anclaY = 0f

    /** Grosor base proporcional a la resolución de la captura (≈6 px en 1080 de ancho). */
    private val base = max(imagen.width, 1) / 180f

    // ---------------------------------------------------------------- Vista

    private val matriz = Matrix()
    private val inversa = Matrix()
    private var escala = 1f

    private val pinturaImagen = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pinturaTrazo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val pinturaSombra = Paint().apply { color = 0x99000000.toInt() }
    private val pinturaBorde = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2f * resources.displayMetrics.density
        pathEffect = DashPathEffect(floatArrayOf(18f, 10f), 0f)
    }
    private val pinturaEtiqueta = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics)
    }
    private val pinturaFondoEtiqueta = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC000000.toInt() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        escala = min(w / imagen.width.toFloat(), h / imagen.height.toFloat())
        val dx = (w - imagen.width * escala) / 2f
        val dy = (h - imagen.height * escala) / 2f
        matriz.setScale(escala, escala)
        matriz.postTranslate(dx, dy)
        matriz.invert(inversa)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.concat(matriz)
        canvas.drawBitmap(imagen, 0f, 0f, pinturaImagen)
        for (t in trazos) pintar(canvas, t)
        canvas.restore()

        val sel = seleccion
        if (sel != null) {
            val r = RectF(sel)
            matriz.mapRect(r)
            val img = RectF(0f, 0f, imagen.width.toFloat(), imagen.height.toFloat())
            matriz.mapRect(img)
            // Oscurecemos lo que queda fuera de la zona elegida.
            canvas.drawRect(img.left, img.top, img.right, r.top, pinturaSombra)
            canvas.drawRect(img.left, r.bottom, img.right, img.bottom, pinturaSombra)
            canvas.drawRect(img.left, r.top, r.left, r.bottom, pinturaSombra)
            canvas.drawRect(r.right, r.top, img.right, r.bottom, pinturaSombra)
            canvas.drawRect(r, pinturaBorde)
            etiqueta(canvas, "${sel.width().toInt()} × ${sel.height().toInt()}", r.left, r.top)
        } else if (herramienta == Herramienta.RECORTAR) {
            etiqueta(canvas, "Arrastra para elegir la zona", width / 2f, height / 2f, centrado = true)
        }
    }

    private fun etiqueta(c: Canvas, texto: String, x: Float, y: Float, centrado: Boolean = false) {
        val pad = 6f * resources.displayMetrics.density
        val ancho = pinturaEtiqueta.measureText(texto)
        val alto = pinturaEtiqueta.textSize
        var left = if (centrado) x - ancho / 2 - pad else x
        var top = if (centrado) y - alto / 2 - pad else y - alto - pad * 2 - 4
        if (top < 0) top = y + 4
        left = left.coerceIn(0f, max(0f, width - ancho - pad * 2))
        val fondo = RectF(left, top, left + ancho + pad * 2, top + alto + pad * 2)
        c.drawRoundRect(fondo, pad, pad, pinturaFondoEtiqueta)
        c.drawText(texto, left + pad, top + pad + alto * 0.8f, pinturaEtiqueta)
    }

    private fun pintar(c: Canvas, t: Trazo) {
        val p = pinturaTrazo
        p.color = t.color
        p.alpha = 255
        p.style = Paint.Style.STROKE
        p.strokeWidth = t.ancho
        p.setShadowLayer(0f, 0f, 0f, 0)
        when (t) {
            is TrazoLibre -> {
                if (t.marcador) {
                    p.alpha = 110
                    p.strokeCap = Paint.Cap.SQUARE
                }
                c.drawPath(t.path, p)
                p.strokeCap = Paint.Cap.ROUND
            }
            is TrazoLinea -> {
                c.drawLine(t.x1, t.y1, t.x2, t.y2, p)
                if (t.flecha) {
                    val ang = atan2(t.y2 - t.y1, t.x2 - t.x1)
                    val largo = t.ancho * 4.5f
                    val punta = Path().apply {
                        moveTo(t.x2 + cos(ang) * t.ancho, t.y2 + sin(ang) * t.ancho)
                        lineTo(t.x2 - largo * cos(ang - 0.45f), t.y2 - largo * sin(ang - 0.45f))
                        lineTo(t.x2 - largo * cos(ang + 0.45f), t.y2 - largo * sin(ang + 0.45f))
                        close()
                    }
                    p.style = Paint.Style.FILL
                    c.drawPath(punta, p)
                }
            }
            is TrazoRect -> c.drawRect(min(t.x1, t.x2), min(t.y1, t.y2), max(t.x1, t.x2), max(t.y1, t.y2), p)
            is TrazoTexto -> {
                p.style = Paint.Style.FILL
                p.textSize = t.ancho
                p.setShadowLayer(t.ancho / 10f, 0f, 0f,
                    if (t.color == Color.BLACK) Color.WHITE else Color.BLACK)
                c.drawText(t.texto, t.x, t.y, p)
                p.setShadowLayer(0f, 0f, 0f, 0)
            }
        }
    }

    // ---------------------------------------------------------------- Táctil

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val pt = floatArrayOf(e.x, e.y)
        inversa.mapPoints(pt)
        val x = pt[0].coerceIn(0f, imagen.width.toFloat())
        val y = pt[1].coerceIn(0f, imagen.height.toFloat())

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> empezar(x, y)
            MotionEvent.ACTION_MOVE -> mover(x, y)
            MotionEvent.ACTION_UP -> terminar(x, y)
            MotionEvent.ACTION_CANCEL -> { actual = null }
        }
        invalidate()
        return true
    }

    private fun empezar(x: Float, y: Float) {
        anclaX = x; anclaY = y
        actual = when (herramienta) {
            Herramienta.RECORTAR -> { seleccion = RectF(x, y, x, y); null }
            Herramienta.LAPIZ -> TrazoLibre(Path().apply { moveTo(x, y) }, color, base, false)
            Herramienta.MARCADOR -> TrazoLibre(Path().apply { moveTo(x, y) }, color, base * 4f, true)
            Herramienta.LINEA -> TrazoLinea(x, y, x, y, color, base, false)
            Herramienta.FLECHA -> TrazoLinea(x, y, x, y, color, base, true)
            Herramienta.RECTANGULO -> TrazoRect(x, y, x, y, color, base)
            Herramienta.TEXTO -> null
        }
        actual?.let { trazos.add(it) }
    }

    private fun mover(x: Float, y: Float) {
        when (val t = actual) {
            is TrazoLibre -> t.path.lineTo(x, y)
            is TrazoLinea -> { t.x2 = x; t.y2 = y }
            is TrazoRect -> { t.x2 = x; t.y2 = y }
            else -> if (herramienta == Herramienta.RECORTAR) {
                seleccion = RectF(min(anclaX, x), min(anclaY, y), max(anclaX, x), max(anclaY, y))
            }
        }
    }

    private fun terminar(x: Float, y: Float) {
        mover(x, y)
        val minimo = base * 2
        when (herramienta) {
            Herramienta.RECORTAR -> seleccion?.let {
                if (it.width() < minimo || it.height() < minimo) seleccion = null
            }
            Herramienta.TEXTO -> alPedirTexto?.invoke(x, y)
            else -> {
                // Un toque sin mover en línea/flecha/rectángulo no deja nada.
                val t = actual
                if ((t is TrazoLinea && abs(t.x2 - t.x1) + abs(t.y2 - t.y1) < minimo) ||
                    (t is TrazoRect && (abs(t.x2 - t.x1) < minimo || abs(t.y2 - t.y1) < minimo))
                ) trazos.remove(t)
            }
        }
        actual = null
    }

    // ---------------------------------------------------------------- API

    fun agregarTexto(x: Float, y: Float, texto: String) {
        if (texto.isBlank()) return
        trazos.add(TrazoTexto(x, y, texto, color, base * 7f))
        invalidate()
    }

    /** Deshace el último trazo; si no quedan, quita la selección. */
    fun deshacer() {
        if (trazos.isNotEmpty()) trazos.removeAt(trazos.lastIndex) else seleccion = null
        invalidate()
    }

    /** Imagen final: captura + anotaciones, recortada a la zona elegida. */
    fun renderizar(): Bitmap {
        val salida = imagen.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(salida)
        for (t in trazos) pintar(c, t)
        val sel = seleccion ?: return salida
        val r = Rect(
            sel.left.toInt().coerceIn(0, salida.width - 1),
            sel.top.toInt().coerceIn(0, salida.height - 1),
            sel.right.toInt().coerceIn(1, salida.width),
            sel.bottom.toInt().coerceIn(1, salida.height)
        )
        if (r.width() <= 0 || r.height() <= 0) return salida
        val recortada = Bitmap.createBitmap(salida, r.left, r.top, r.width(), r.height())
        if (recortada != salida) salida.recycle()
        return recortada
    }
}
