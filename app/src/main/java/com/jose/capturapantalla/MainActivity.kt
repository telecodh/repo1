package com.jose.capturapantalla

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Pantalla principal: pide los permisos necesarios y arranca el servicio
 * que muestra la burbuja flotante de captura.
 */
class MainActivity : Activity() {

    private lateinit var estado: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(crearVista())
    }

    override fun onResume() {
        super.onResume()
        actualizarEstado()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun crearVista(): LinearLayout {
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(48), dp(24), dp(24))
            setBackgroundColor(Color.WHITE)
        }
        raiz.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#1E88E5"))
        })
        raiz.addView(TextView(this).apply {
            text = "Pulsa «Iniciar» y aparecerá un botón flotante con una cámara. " +
                "Tócalo en cualquier momento para hacer una captura de pantalla. " +
                "Puedes arrastrarlo para moverlo.\n\n" +
                "Las capturas se guardan en Imágenes › Capturas."
            textSize = 16f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(16), 0, dp(24))
        })
        estado = TextView(this).apply {
            textSize = 14f
            setPadding(0, 0, 0, dp(16))
        }
        raiz.addView(estado)

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) }

        raiz.addView(Button(this).apply {
            text = "Iniciar botón flotante"
            setOnClickListener { iniciar() }
        }, params)
        raiz.addView(Button(this).apply {
            text = "Detener"
            setOnClickListener {
                startService(Intent(this@MainActivity, CapturaService::class.java)
                    .setAction(CapturaService.ACCION_DETENER))
                estado.postDelayed({ actualizarEstado() }, 300)
            }
        }, params)
        return raiz
    }

    private fun actualizarEstado() {
        estado.text = if (CapturaService.activo) "● Botón flotante activo" else "○ Botón flotante detenido"
        estado.setTextColor(if (CapturaService.activo) Color.parseColor("#2E7D32") else Color.GRAY)
    }

    private fun iniciar() {
        // 1) Notificaciones (Android 13+), necesarias para ver la notificación del servicio.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            return
        }
        // 2) Permiso para mostrar sobre otras apps.
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Activa «Mostrar sobre otras apps» y vuelve", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        // 3) Permiso de captura de pantalla (MediaProjection).
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_CAPTURA)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Continuamos aunque se deniegue: la app funciona igual, solo sin notificación visible.
        if (requestCode == REQ_NOTIF) iniciar()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURA) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Permiso de captura denegado", Toast.LENGTH_SHORT).show()
            return
        }
        val servicio = Intent(this, CapturaService::class.java)
            .setAction(CapturaService.ACCION_INICIAR)
            .putExtra(CapturaService.EXTRA_CODIGO, resultCode)
            .putExtra(CapturaService.EXTRA_DATOS, data)
        startForegroundService(servicio)
        Toast.makeText(this, "Botón flotante activado", Toast.LENGTH_SHORT).show()
        moveTaskToBack(true)
    }

    companion object {
        private const val REQ_CAPTURA = 1
        private const val REQ_NOTIF = 2
    }
}
