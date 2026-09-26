package com.jose.capturapantalla

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.jose.capturapantalla.EditorView.Herramienta

/**
 * Editor estilo Lightshot: recortar zona, anotar y luego copiar, compartir o guardar.
 */
class EditorActivity : Activity() {

    private lateinit var lienzo: EditorView
    private val botonesHerramienta = mutableMapOf<Herramienta, TextView>()
    private val botonesColor = mutableMapOf<Int, View>()
    private var ocupado = false

    private val colores = intArrayOf(
        Color.parseColor("#E53935"), Color.parseColor("#FDD835"), Color.parseColor("#43A047"),
        Color.parseColor("#1E88E5"), Color.parseColor("#8E24AA"), Color.BLACK, Color.WHITE
    )

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val imagen = CapturaService.capturaPendiente
        if (imagen == null) {
            finish()
            return
        }
        lienzo = EditorView(this, imagen).apply {
            alPedirTexto = { x, y -> pedirTexto(x, y) }
        }

        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#101418"))
        }
        raiz.addView(barraSuperior(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        raiz.addView(FrameLayout(this).apply { addView(lienzo) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        raiz.addView(filaDesplazable(filaHerramientas()))
        raiz.addView(filaDesplazable(filaColores()))

        // Android 15 dibuja bajo las barras del sistema: respetamos sus márgenes.
        raiz.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(b.left, b.top, b.right, b.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(raiz)
        seleccionarHerramienta(Herramienta.RECORTAR)
        seleccionarColor(colores[0])
    }

    override fun onStart() {
        super.onStart()
        CapturaService.instancia?.burbujaVisible(false)
    }

    override fun onStop() {
        super.onStop()
        CapturaService.instancia?.burbujaVisible(true)
        if (isFinishing) CapturaService.capturaPendiente = null
    }

    // ---------------------------------------------------------------- UI

    private fun boton(texto: String, alPulsar: () -> Unit) = TextView(this).apply {
        text = texto
        setTextColor(Color.WHITE)
        textSize = 15f
        gravity = Gravity.CENTER
        setPadding(dp(12), 0, dp(12), 0)
        isClickable = true
        isFocusable = true
        setOnClickListener { alPulsar() }
    }

    private fun barraSuperior() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Color.parseColor("#1B2127"))
        val alto = ViewGroup.LayoutParams.MATCH_PARENT
        addView(boton("✕") { finish() }.apply { textSize = 20f }, LinearLayout.LayoutParams(dp(48), alto))
        addView(View(context), LinearLayout.LayoutParams(0, alto, 1f))
        addView(boton("↶") { lienzo.deshacer() }.apply { textSize = 22f }, LinearLayout.LayoutParams(dp(48), alto))
        addView(boton("Copiar") { copiar() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, alto))
        addView(boton("Compartir") { compartir() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, alto))
        addView(boton("Guardar") { guardar() }.apply {
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#64B5F6"))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, alto))
    }

    private fun filaDesplazable(fila: View) = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(Color.parseColor("#1B2127"))
        addView(fila)
    }

    private fun filaHerramientas() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(6), dp(6), dp(6), dp(2))
        val lista = listOf(
            Herramienta.RECORTAR to "⬚ Zona",
            Herramienta.LAPIZ to "✎ Lápiz",
            Herramienta.LINEA to "╱ Línea",
            Herramienta.FLECHA to "➜ Flecha",
            Herramienta.RECTANGULO to "▭ Marco",
            Herramienta.MARCADOR to "▌ Marcador",
            Herramienta.TEXTO to "T Texto"
        )
        for ((h, nombre) in lista) {
            val b = boton(nombre) { seleccionarHerramienta(h) }.apply { textSize = 14f }
            botonesHerramienta[h] = b
            addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply {
                marginEnd = dp(4)
            })
        }
    }

    private fun filaColores() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(10), dp(6), dp(10), dp(10))
        for (c in colores) {
            val v = View(context).apply {
                isClickable = true
                contentDescription = "Color"
                setOnClickListener { seleccionarColor(c) }
            }
            botonesColor[c] = v
            addView(v, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(12) })
        }
    }

    private fun seleccionarHerramienta(h: Herramienta) {
        lienzo.herramienta = h
        for ((k, b) in botonesHerramienta) {
            b.background = if (k == h) GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#1E88E5"))
            } else null
        }
    }

    private fun seleccionarColor(c: Int) {
        lienzo.color = c
        for ((k, v) in botonesColor) {
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(k)
                if (k == c) setStroke(dp(3), Color.parseColor("#64B5F6"))
                else setStroke(dp(1), Color.parseColor("#55FFFFFF"))
            }
        }
    }

    private fun pedirTexto(x: Float, y: Float) {
        val campo = EditText(this).apply {
            hint = "Escribe el texto"
            setSingleLine(false)
        }
        val marco = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(campo)
        }
        AlertDialog.Builder(this)
            .setTitle("Añadir texto")
            .setView(marco)
            .setPositiveButton("Añadir") { _, _ -> lienzo.agregarTexto(x, y, campo.text.toString()) }
            .setNegativeButton("Cancelar", null)
            .show()
        campo.requestFocus()
    }

    // ---------------------------------------------------------------- Acciones

    /** Genera la imagen final en segundo plano y ejecuta [accion] con ella. */
    private fun conResultado(accion: (Bitmap) -> Unit) {
        if (ocupado) return
        ocupado = true
        val bmp = lienzo.renderizar()
        Thread {
            try {
                accion(bmp)
            } catch (e: Exception) {
                runOnUiThread {
                    ocupado = false
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun guardar() = conResultado { bmp ->
        val uri = Imagenes.guardarEnGaleria(this, bmp)
        runOnUiThread {
            Toast.makeText(this,
                if (uri != null) "Guardada en Imágenes/Capturas" else "No se pudo guardar",
                Toast.LENGTH_SHORT).show()
            if (uri != null) finish() else ocupado = false
        }
    }

    private fun copiar() = conResultado { bmp ->
        val uri = Imagenes.uriTemporal(this, bmp)
        runOnUiThread {
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newUri(contentResolver, "Captura", uri))
            // Android 13+ ya muestra su propio aviso al copiar.
            if (Build.VERSION.SDK_INT < 33) {
                Toast.makeText(this, "Imagen copiada", Toast.LENGTH_SHORT).show()
            }
            finish()
        }
    }

    private fun compartir() = conResultado { bmp ->
        val uri = Imagenes.uriTemporal(this, bmp)
        runOnUiThread {
            val envio = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("Captura", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(envio, "Compartir captura"))
            finish()
        }
    }
}
