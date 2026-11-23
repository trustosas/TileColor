package com.trust.tilecolor

import android.app.Activity
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.util.TypedValue
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    private lateinit var log: TextView
    private lateinit var hex: EditText
    private var service: IOverlayService? = null
    private var pending: ((IOverlayService) -> Unit)? = null

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(packageName, OverlayService::class.java.name))
            .daemon(false).processNameSuffix("overlay").debuggable(false).version(1)
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) { say("Service binder invalid"); return }
            val s = IOverlayService.Stub.asInterface(binder)
            service = s
            pending?.let { pending = null; it(s) }
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        say(if (result == PackageManager.PERMISSION_GRANTED) "Shizuku permission granted" else "Shizuku permission denied")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(permListener)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        hex = EditText(this).apply { setText("FF6A00"); hint = "RRGGBB" }
        log = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f) }
        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { action() }
            root.addView(this)
        }
        root.addView(TextView(this).apply { text = "Active tile color (hex RRGGBB)" })
        root.addView(hex)
        button("Grant Shizuku permission") { grant() }
        button("Apply") { call(true) }
        button("Revert") { call(false) }
        root.addView(log)
        setContentView(ScrollView(this).apply { addView(root) })
        say(status())
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permListener)
        try { Shizuku.unbindUserService(args, conn, false) } catch (_: Throwable) {}
        super.onDestroy()
    }

    private fun say(s: String) = runOnUiThread { log.append(s + "\n") }

    private fun status(): String = when {
        !Shizuku.pingBinder() -> "Shizuku is not running. Start it first."
        Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> "Shizuku running, permission not granted."
        else -> "Shizuku ready."
    }

    private fun grant() {
        if (!Shizuku.pingBinder()) { say("Shizuku is not running."); return }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) say("Already granted")
        else Shizuku.requestPermission(1)
    }

    private fun call(enable: Boolean) {
        if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            say(status()); return
        }
        val color = try {
            Color.parseColor("#" + hex.text.toString().trim().removePrefix("#"))
        } catch (e: Throwable) { say("Bad hex color"); return }
        val action: (IOverlayService) -> Unit = { s ->
            Thread { say(try { s.apply(enable, color) } catch (e: Throwable) { "Failed: $e" }) }.start()
        }
        val s = service
        if (s != null && s.asBinder().pingBinder()) action(s)
        else {
            pending = action
            say("Starting Shizuku service...")
            try { Shizuku.bindUserService(args, conn) } catch (e: Throwable) { say("Bind failed: $e") }
        }
    }
}
