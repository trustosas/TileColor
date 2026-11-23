package com.trust.tilecolor

import android.os.IBinder
import android.util.TypedValue
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.InvocationTargetException
import kotlin.system.exitProcess

/** Runs inside the Shizuku user-service process (uid shell). */
class OverlayService : IOverlayService.Stub() {

    companion object {
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

    init {
        try { HiddenApiBypass.addHiddenApiExemptions("L") } catch (_: Throwable) {}
    }

    override fun destroy() { exitProcess(0) }

    override fun apply(enable: Boolean, color: Int): String = try {
        doApply(enable, color)
    } catch (e: Throwable) {
        val t = (e as? InvocationTargetException)?.targetException ?: e
        "Failed: $t"
    }

    private fun doApply(enable: Boolean, color: Int): String {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "overlay") as? IBinder
            ?: return "Failed: overlay service not found"
        val iom = Class.forName("android.content.om.IOverlayManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder) ?: return "Failed: asInterface returned null"

        val idCls = Class.forName("android.content.om.OverlayIdentifier")
        val txCls = Class.forName("android.content.om.OverlayManagerTransaction")
        val txBuilderCls = Class.forName("android.content.om.OverlayManagerTransaction\$Builder")
        val id = idCls.getConstructor(String::class.java, String::class.java).newInstance(OWNER, NAME)
        val tx = txBuilderCls.getConstructor().newInstance()

        if (enable) {
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
            .invoke(iom, built)
        return if (enable) "Applied. Open the shade; if unchanged run: killall com.android.systemui" else "Reverted."
    }
}
