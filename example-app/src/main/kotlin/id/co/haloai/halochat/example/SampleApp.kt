package id.co.haloai.halochat.example

import android.app.Application
import id.co.haloai.halochat.ui.HaloChatColor
import id.co.haloai.halochat.ui.HaloChatWidget
import id.co.haloai.halochat.ui.HaloChatWidgetConfig

class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        HaloChatWidget.setup(
            this,
            HaloChatWidgetConfig().setEnableNotification(true),
            HaloChatColor()
                .setNavigationColor(0xFF1F4E8C.toInt())
                .setRightBubbleColor(0xFFDCE8F7.toInt())
                .setSendContainerColor(0xFF1F4E8C.toInt()),
        )
    }
}
