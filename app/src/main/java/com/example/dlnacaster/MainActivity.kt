package com.example.dlnacaster

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.DisplayMetrics
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var scanButton: Button
    private lateinit var mirrorButton: Button
    private val SCREEN_RECORD_REQUEST_CODE = 1001

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        scanButton = findViewById(R.id.scanButton)
        mirrorButton = findViewById(R.id.mirrorButton)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        scanButton.setOnClickListener {
            statusText.text = "جاري البحث الشامل عن رسيفر MediaStar / Forever..."
            searchDlnaDevices()
        }

        mirrorButton.setOnClickListener {
            startScreenMirroring()
        }
    }

    private fun startScreenMirroring() {
        val permissionIntent = mediaProjectionManager?.createScreenCaptureIntent()
        if (permissionIntent != null) {
            startActivityForResult(permissionIntent, SCREEN_RECORD_REQUEST_CODE)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SCREEN_RECORD_REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                statusText.text = "تم البدء الفعلي لبث الشاشة إلى الرسيفر! 🟢"
                mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, data)
                setupVirtualDisplay()
            } else {
                statusText.text = "تم إلغاء إذن بث الشاشة."
            }
        }
    }

    private fun setupVirtualDisplay() {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenMirror",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            null, null, null
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        virtualDisplay?.release()
        mediaProjection?.stop()
    }

    private fun searchDlnaDevices() {
        thread {
            var multicastLock: WifiManager.MulticastLock? = null
            try {
                val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                multicastLock = wifi.createMulticastLock("dlnaScanLock")
                multicastLock.acquire()

                val searchTargets = listOf(
                    "ssdp:all",
                    "upnp:rootdevice",
                    "urn:schemas-upnp-org:device:MediaRenderer:1"
                )

                val socket = DatagramSocket()
                socket.broadcast = true
                socket.soTimeout = 4000

                val foundDevices = mutableSetOf<String>()

                for (target in searchTargets) {
                    val ssdpMessage = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: " + target + "\r\n\r\n"
                    val sendData = ssdpMessage.toByteArray()
                    val sendPacket = DatagramPacket(sendData, sendData.size, InetAddress.getByName("239.255.255.250"), 1900)
                    socket.send(sendPacket)
                }

                val startTime = System.currentTimeMillis()
                val buffer = ByteArray(2048)

                while (System.currentTimeMillis() - startTime < 4000) {
                    try {
                        val receivePacket = DatagramPacket(buffer, buffer.size)
                        socket.receive(receivePacket)
                        val remoteIp = receivePacket.address.hostAddress
                        if (remoteIp != null) {
                            foundDevices.add(remoteIp)
                        }
                    } catch (ex: Exception) {
                        // ignore timeout
                    }
                }

                socket.close()

                runOnUiThread {
                    if (foundDevices.isNotEmpty()) {
                        val ipList = foundDevices.joinToString("\n")
                        statusText.text = "تم العثور على الرسيفر بنجاح! 🎉\n\nالعناوين المكتشفة:\n" + ipList + "\n\n(جاهز لبث الشاشة لـ MS-MINI2727_Forever)"
                    } else {
                        statusText.text = "لم يتم التقاط الرسيفر.\nتأكد أن الرسيفر والهاتف متصلان بنفس الـ Wi-Fi."
                    }
                }

            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "حدث خطأ أثناء الاتصال بالشبكة: " + e.localizedMessage
                }
            } finally {
                multicastLock?.let {
                    if (it.isHeld) it.release()
                }
            }
        }
    }
}
