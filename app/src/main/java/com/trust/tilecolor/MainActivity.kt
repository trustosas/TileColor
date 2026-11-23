package com.trust.tilecolor

import android.app.Activity
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
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.InvocationTargetException

class MainActivity : Activity() {

    companion object {
        // Overlay owner must match the calling package of the Shizuku shell process.
        private const val OWNER = "com.android.shell"
        private const val NAME = "tilecolor"
        private const val TARGET = "com.android.systemui"
        private val RESOURCES = listOf(
            "ts_icon_bg_color_active",
            "ts_icon_bg_color_active_light",
            "resident_tile_board_active_color_solid",
            "tr_qs_icon_foreground_color_active",
        )
    }

    private lateinit var log: TextView
    private lateinit var hex: EditText

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        say(if (result == PackageManager.PERMISSION_GRANTED) "Shizuku permission granted" else "Shizuku permission denied")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HiddenApiBypass.addHiddenApiExemptions("L")
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
        button("Apply") { run { apply(true) } }
        button("Revert") { run { apply(false) } }
        root.addView(log)
        setContentView(ScrollView(this).apply { addView(root) })
        say(status())
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permListener)
        super.onDestroy()
    }

    private fun say(s: String) { log.append(s + "\n") }

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

    private fun run(block: () -> Unit) {
        try {
            if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                say(status()); return
            }
            block()
        } catch (e: InvocationTargetException) {
            say("Failed: " + (e.targetException?.toString() ?: e.toString()))
        } catch (e: Throwable) {
            say("Failed: $e")
        }
    }

    private fun overlayManager(): Any {
        val wrapped = ShizukuBinderWrapper(SystemServiceHelper.getSystemService("overlay"))
        return Class.forName("android.content.om.IOverlayManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, wrapped)!!
    }

    private fun apply(enable: Boolean) {
        val idCls = Class.forName("android.content.om.OverlayIdentifier")
        val txCls = Class.forName("android.content.om.OverlayManagerTransaction")
        val txBuilderCls = Class.forName("android.content.om.OverlayManagerTransaction\$Builder")
        val id = idCls.getConstructor(String::class.java, String::class.java).newInstance(OWNER, NAME)
        val tx = txBuilderCls.getConstructor().newInstance()

        if (enable) {
            val color = Color.parseColor("#" + hex.text.toString().trim().removePrefix("#"))
            val fabCls = Class.forName("android.content.om.FabricatedOverlay")
            val fabBuilderCls = Class.forName("android.content.om.FabricatedOverlay\$Builder")
            val fb = fabBuilderCls.getConstructor(String::class.java, String::class.java, String::class.java)
                .newInstance(OWNER, NAME, TARGET)
            val setValue = fabBuilderCls.getMethod(
                "setResourceValue", String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
            )
            for (r in RESOURCES) setValue.invoke(fb, "$TARGET:color/$r", TypedValue.TYPE_INT_COLOR_ARGB8, color)
            val overlay = fabBuilderCls.getMethod("build").invoke(fb)
            txBuilderCls.getMethod("registerFabricatedOverlay", fabCls).invoke(tx, overlay)
            txBuilderCls.getMethod("setEnabled", idCls, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(tx, id, true, 0)
        } else {
            txBuilderCls.getMethod("unregisterFabricatedOverlay", idCls).invoke(tx, id)
        }

        val built = txBuilderCls.getMethod("build").invoke(tx)
        Class.forName("android.content.om.IOverlayManager")
            .getMethod("commit", txCls)
            .invoke(overlayManager(), built)
        say(if (enable) "Applied. Open the shade; if unchanged run: killall com.android.systemui" else "Reverted.")
    }
}
