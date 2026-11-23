package com.lex.nimbusvm

object NativeQemu {
    init {
        try {
            System.loadLibrary("nimbusvm")
        } catch (e: UnsatisfiedLinkError) {
            // The APK can still exec QEMU via ProcessBuilder if the JNI lib is missing.
        }
    }

    @JvmStatic external fun spawn(argv: Array<String>): Int
    @JvmStatic external fun waitpid(pid: Int, timeoutMs: Int): Int
    @JvmStatic external fun kill(pid: Int, sig: Int): Int
}
