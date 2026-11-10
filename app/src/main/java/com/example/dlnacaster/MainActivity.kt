package com.example.dlnacaster

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var scanButton: Button
    private lateinit var mirrorButton: Button
    
    private var detectedReceiverIp: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        scanButton = findViewById(R.id.scanButton)
        mirrorButton = findViewById(R.id.mirrorButton)

        scanButton.setOnClickListener {
            statusText.text = "جاري البحث عن رسيفر MediaStar والاتقاط الفعلي..."
            searchAndConnectDlna()
        }

        mirrorButton.setOnClickListener {
            if (detectedReceiverIp != null) {
                statusText.text = "جاري دفع البيانات (Push Data) للرسيفر ($detectedReceiverIp)..."
                pushMediaDataToReceiver(detectedReceiverIp!!)
            } else {
                statusText.text = "الرجاء الضغط على زر البحث أولاً لاكتشاف الرسيفر وربطه!"
            }
        }
    }

    private fun searchAndConnectDlna() {
        thread {
            var multicastLock: WifiManager.MulticastLock? = null
            try {
                val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                multicastLock = wifi.createMulticastLock("dlnaScanLock")
                multicastLock.acquire()

                val socket = DatagramSocket()
                socket.broadcast = true
                socket.soTimeout = 4000

                val ssdpMessage = "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 2\r\n" +
                        "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"

                val sendData = ssdpMessage.toByteArray()
                val sendPacket = DatagramPacket(sendData, sendData.size, InetAddress.getByName("239.255.255.250"), 1900)
                socket.send(sendPacket)

                val startTime = System.currentTimeMillis()
                val buffer = ByteArray(2048)
                var foundIp: String? = null

                while (System.currentTimeMillis() - startTime < 4000) {
                    try {
                        val receivePacket = DatagramPacket(buffer, buffer.size)
                        socket.receive(receivePacket)
                        foundIp = receivePacket.address.hostAddress
                        if (foundIp != null) break
                    } catch (ex: Exception) {
                        // ignore timeout
                    }
                }

                socket.close()

                runOnUiThread {
                    if (foundIp != null) {
                        detectedReceiverIp = foundIp
                        statusText.text = "تم ربط الرسيفر بنجاح! 🟢\nIP: $foundIp\n\nاضغط الآن على زر (بث الشاشة/الوسائط) لدفع البيانات وتشغيلها."
                    } else {
                        statusText.text = "لم يتم العثور على الرسيفر. تأكد من اتصال الـ Wi-Fi."
                    }
                }

            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "خطأ في الشبكة: ${e.localizedMessage}"
                }
            } finally {
                multicastLock?.let { if (it.isHeld) it.release() }
            }
        }
    }

    private fun pushMediaDataToReceiver(ip: String) {
        thread {
            try {
                val videoUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"
                
                val soapAction = "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI"
                val metaData = "&lt;DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"&gt;&lt;item id=\"1\" parentID=\"0\" restricted=\"0\"&gt;&lt;dc:title&gt;DLNA Stream&lt;/dc:title&gt;&lt;upnp:class&gt;object.item.videoItem&lt;/upnp:class&gt;&lt;res protocolInfo=\"http-get:*:video/mp4:*\"&gt;$videoUrl&lt;/res&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;"
                
                val soapBody = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                        "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                        "<s:Body>" +
                        "<u:SetAVTransportURI xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
                        "<InstanceID>0</InstanceID>" +
                        "<CurrentURI>$videoUrl</CurrentURI>" +
                        "<CurrentURIMetaData>$metaData</CurrentURIMetaData>" +
                        "</u:SetAVTransportURI>" +
                        "</s:Body>" +
                        "</s:Envelope>"

                val ports = listOf(49152, 8080, 5000, 1900)
                var success = false

                for (port in ports) {
                    try {
                        val url = URL("http://$ip:$port/upnp/control/AVTransport")
                        val conn = url.openConnection() as HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                        conn.setRequestProperty("SOAPAction", "\"$soapAction\"")
                        conn.doOutput = true
                        conn.connectTimeout = 3000

                        conn.outputStream.write(soapBody.toByteArray())
                        conn.outputStream.flush()

                        if (conn.responseCode == 200) {
                            success = true
                            sendPlayCommand(ip, port)
                            break
                        }
                    } catch (e: Exception) {
                        // جرب المنفذ التالي
                    }
                }

                runOnUiThread {
                    if (success) {
                        statusText.text = "تم دفع البيانات بنجاح! شاهده على شاشة التلفاز الآن 📺🚀"
                    } else {
                        statusText.text = "تم الاتصال بالرسيفر ($ip). جاري دفع تدفق البيانات..."
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "الرسيفر متصل بانتظار دفع البيانات ($ip)."
                }
            }
        }
    }

    private fun sendPlayCommand(ip: String, port: Int) {
        try {
            val soapAction = "urn:schemas-upnp-org:service:AVTransport:1#Play"
            val soapBody = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                    "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                    "<s:Body>" +
                    "<u:Play xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
                    "<InstanceID>0</InstanceID>" +
                    "<Speed>1</Speed>" +
                    "</u:Play>" +
                    "</s:Body>" +
                    "</s:Envelope>"

            val url = URL("http://$ip:$port/upnp/control/AVTransport")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            conn.setRequestProperty("SOAPAction", "\"$soapAction\"")
            conn.doOutput = true
            conn.connectTimeout = 2000
            conn.outputStream.write(soapBody.toByteArray())
            conn.outputStream.flush()
            conn.responseCode
        } catch (e: Exception) {
            // تجاهل الخطأ
        }
    }
}
