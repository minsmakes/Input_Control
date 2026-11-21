package io.inputcontrol.nativebridge

internal object Native {
    init {
        System.loadLibrary("input_control")
    }

    external fun dispatch(op: Int, id: Int, dx: Float, dy: Float, session: Long = 0): Long

    external fun openWifi(
        id: Long,
        address: String,
        key: ByteArray,
        pin: String,
        model: String,
    ): Int

    external fun wantFocus(on: Boolean)

    external fun dashboard(id: Long, kind: Int): String?

    external fun wifiStatus(): String

    external fun confirmWifi(id: Long, code: String)

    external fun closeWifi()

    external fun composing(): Int

    external fun snapshot(): String
}
