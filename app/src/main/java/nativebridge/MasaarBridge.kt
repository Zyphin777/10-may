package com.example.offlineroutingapp.nativebridge


object MasaarBridge {
    init {
        System.loadLibrary("masaar") // اسم المكتبة اللي NDK هيبنيها
    }

    external fun setNodeId(id: String)
    external fun buildMessage(payload: String, dst: String): String
    external fun handleIncoming(msg: String): String
    external fun buildHelloMessage(): String

}