package com.example.offlineroutingapp.network

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

object UdpBroadcastSender {

    fun sendHello(nodeId: String, port: Int = 9999) {
        Thread {
            try {
                val socket = DatagramSocket()
                socket.broadcast = true

                val message = "HELLO:$nodeId"
                val data = message.toByteArray()

                val address = InetAddress.getByName("255.255.255.255")

                val packet = DatagramPacket(data, data.size, address, port)
                socket.send(packet)

                socket.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }
}