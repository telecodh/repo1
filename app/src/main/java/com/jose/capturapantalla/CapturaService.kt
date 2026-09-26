package com.jose.capturapantalla

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import kotlin.math.abs

/**
 * Servicio en primer plano que:
 *  - mantiene la sesión de MediaProjection (un único VirtualDisplay, requisito de Android 14+),
 *  - muestra una burbuja flotante arrastrable,
 *  - al tocar la burbuja, la oculta, toma el último fotograma y lo guarda en Imágenes/Capturas.
 */
class CapturaService : Service() {

    private lateinit var windowManager: WindowManager
    private val principal = Handler(Looper.getMainLooper())
    private lateinit var hilo: HandlerThread
    private lateinit var fondo: Handler

    private var proyeccion: MediaProjection? = null
    private var pantallaVirtual: VirtualDisplay? = null
    private var lector: ImageReader? = null
    private var ultimaImagen: Image? = null   // solo se toca desde el hilo "fondo"
    private var ancho = 0
    private var alto = 0
    private var densidad = 0

    private var burbuja: ImageView? = null
    private var capturando = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        hilo = HandlerThread("captura").also { it.start() }
        fondo = Handler(hilo.looper)
        crearCanal()
        instancia = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACCION_DETENER -> {
                detener()
                return START_NOT_STICKY
            }
            ACCION_INICIAR -> {
                // Android 14+: hay que estar en primer plano ANTES de obtener la proyección.
                val notif = crearNotificacion()
                startForeground(ID_NOTIF, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                if (proyeccion == null) {
                    val codigo = intent.getIntExtra(EXTRA_CODIGO, Activity.RESULT_CANCELED)
                    val datos: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(EXTRA_DATOS, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATOS)
                    }
                    if (datos == null || !iniciarProyeccion(codigo, datos)) {
                        Toast.makeText(this, "No se pudo iniciar la captura", Toast.LENGTH_SHORT).show()
                        detener()
                        return START_NOT_STICKY
                    }
                }
                mostrarBurbuja()
                activo = true
            }
        }
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- Proyección

    private fun iniciarProyeccion(codigo: Int, datos: Intent): Boolean {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = try {
            mpm.getMediaProjection(codigo, datos)
        } catch (e: Exception) {
            null
        } ?: return false
        proyeccion = p

        // El callback debe registrarse antes de crear el VirtualDisplay (Android 14+).
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                principal.post { detener() }
            }
        }, principal)

        leerTamano()
        val nuevoLector = crearLector(ancho, alto)
        lector = nuevoLector
        pantallaVirtual = p.createVirtualDisplay(
            "CapturaPantalla", ancho, alto, densidad,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            nuevoLector.surface, null, fondo
        )
        return pantallaVirtual != null
    }

    private fun leerTamano() {
        densidad = resources.displayMetrics.densityDpi
        if (Build.VERSION.SDK_INT >= 30) {
            val b = windowManager.maximumWindowMetrics.bounds
            ancho = b.width()
            alto = b.height()
        } else {
            val p = Point()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(p)
            ancho = p.x
            alto = p.y
        }
    }

    private fun crearLector(w: Int, h: Int): ImageReader {
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        // Guardamos siempre el fotograma más reciente para poder capturarlo al instante.
        r.setOnImageAvailableListener({ reader ->
            val img = try { reader.acquireLatestImage() } catch (e: Exception) { null }
            if (img != null) {
                ultimaImagen?.close()
                ultimaImagen = img
            }
        }, fondo)
        return r
    }

    /** Al girar la pantalla adaptamos el VirtualDisplay al nuevo tamaño. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val vd = pantallaVirtual ?: return
        leerTamano()
        val w = ancho
        val h = alto
        val d = densidad
        fondo.post {
            val viejo = lector
            ultimaImagen?.close()
            ultimaImagen = null
            val nuevo = crearLector(w, h)
            lector = nuevo
            vd.resize(w, h, d)
            vd.surface = nuevo.surface
            viejo?.close()
        }
    }

    // ---------------------------------------------------------------- Captura

    private fun capturar() {
        if (capturando) return
        capturando = true
        burbuja?.visibility = View.INVISIBLE
        // Esperamos a que la burbuja desaparezca de pantalla y llegue un fotograma nuevo.
        fondo.postDelayed({
            val bitmap = ultimaImagen?.let { aBitmap(it) }
            principal.post {
                capturando = false
                // La burbuja debe estar visible al abrir el editor (requisito de Android 15
                // para abrir actividades desde segundo plano con permiso de superposición).
                burbuja?.visibility = View.VISIBLE
                if (bitmap == null) {
                    Toast.makeText(this, "No se pudo hacer la captura", Toast.LENGTH_SHORT).show()
                } else {
                    abrirEditor(bitmap)
                }
            }
        }, RETARDO_MS)
    }

    private fun abrirEditor(bitmap: Bitmap) {
        capturaPendiente = bitmap
        try {
            startActivity(
                Intent(this, EditorActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        } catch (e: Exception) {
            // Si el sistema no deja abrir el editor, al menos guardamos la captura.
            capturaPendiente = null
            fondo.post {
                val ok = Imagenes.guardarEnGaleria(this, bitmap) != null
                principal.post {
                    Toast.makeText(this,
                        if (ok) "Captura guardada en Imágenes/Capturas" else "No se pudo guardar",
                        Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** El editor oculta la burbuja mientras está abierto. */
    fun burbujaVisible(visible: Boolean) {
        burbuja?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun aBitmap(img: Image): Bitmap {
        val plano = img.planes[0]
        val buffer = plano.buffer
        buffer.rewind()
        val pixelStride = plano.pixelStride
        val relleno = plano.rowStride - pixelStride * img.width
        val tmp = Bitmap.createBitmap(img.width + relleno / pixelStride, img.height, Bitmap.Config.ARGB_8888)
        tmp.copyPixelsFromBuffer(buffer)
        if (relleno == 0) return tmp
        val recortado = Bitmap.createBitmap(tmp, 0, 0, img.width, img.height)
        tmp.recycle()
        return recortado
    }

    // ---------------------------------------------------------------- Burbuja

    @SuppressLint("ClickableViewAccessibility")
    private fun mostrarBurbuja() {
        if (burbuja != null) return
        val tam = (56 * resources.displayMetrics.density).toInt()
        val pad = (14 * resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            tam, tam,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = (resources.displayMetrics.heightPixels * 0.3).toInt()
        }
        val vista = ImageView(this).apply {
            setImageResource(R.drawable.ic_camara)
            setBackgroundResource(R.drawable.fondo_burbuja)
            setPadding(pad, pad, pad, pad)
            contentDescription = "Hacer captura"
        }

        val umbral = 10 * resources.displayMetrics.density
        var inicioX = 0
        var inicioY = 0
        var tocX = 0f
        var tocY = 0f
        var movido = false
        vista.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    inicioX = params.x; inicioY = params.y
                    tocX = e.rawX; tocY = e.rawY
                    movido = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - tocX
                    val dy = e.rawY - tocY
                    if (!movido && (abs(dx) > umbral || abs(dy) > umbral)) movido = true
                    if (movido) {
                        params.x = inicioX + dx.toInt()
                        params.y = inicioY + dy.toInt()
                        windowManager.updateViewLayout(v, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!movido) {
                        v.performClick()
                        capturar()
                    }
                    true
                }
                else -> false
            }
        }
        windowManager.addView(vista, params)
        burbuja = vista
    }

    // ---------------------------------------------------------------- Notificación

    private fun crearCanal() {
        val canal = NotificationChannel(CANAL, "Captura de pantalla", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
    }

    private fun crearNotificacion(): Notification {
        val abrir = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val parar = PendingIntent.getService(
            this, 1, Intent(this, CapturaService::class.java).setAction(ACCION_DETENER),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_camara)
            .setContentTitle("Captura Pantalla activa")
            .setContentText("Toca el botón flotante para capturar y editar")
            .setContentIntent(abrir)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Detener", parar).build())
            .build()
    }

    // ---------------------------------------------------------------- Parada

    private fun detener() {
        activo = false
        burbuja?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        burbuja = null
        val vd = pantallaVirtual
        val lec = lector
        pantallaVirtual = null
        lector = null
        fondo.post {
            ultimaImagen?.close()
            ultimaImagen = null
            vd?.release()
            lec?.close()
        }
        proyeccion?.let { p -> proyeccion = null; try { p.stop() } catch (_: Exception) {} }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (instancia === this) instancia = null
        if (activo) detener()
        hilo.quitSafely()
        super.onDestroy()
    }

    companion object {
        const val ACCION_INICIAR = "com.jose.capturapantalla.INICIAR"
        const val ACCION_DETENER = "com.jose.capturapantalla.DETENER"
        const val EXTRA_CODIGO = "codigo"
        const val EXTRA_DATOS = "datos"
        private const val CANAL = "captura"
        private const val ID_NOTIF = 1
        private const val RETARDO_MS = 300L

        @Volatile
        var activo = false
            private set

        /** Servicio en marcha (para que el editor muestre/oculte la burbuja). */
        var instancia: CapturaService? = null
            private set

        /** Captura recién hecha que el editor recoge al abrirse. */
        @Volatile
        var capturaPendiente: Bitmap? = null
    }
}
