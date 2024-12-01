package io.github.ichizero.protocgen.connect.ktor

import com.connectrpc.protocgen.connect.internal.Plugin

class Main {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            Plugin.run(Generator())
        }
    }
}
