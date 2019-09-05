package com.aqil.webrtc.utils

/**
 * AppRTCUtils provides helper functions for managing thread safety.
 */
object AppRTCUtils {

    /**
     * Helper method for building a string of thread information.
     */
    val threadInfo: String
        get() = ("@[name=" + Thread.currentThread().name + ", id=" + Thread.currentThread().id
                + "]")

    /**
     * Helper method which throws an exception  when an assertion has failed.
     */
    fun assertIsTrue(condition: Boolean) {
        if (!condition) {
            throw AssertionError("Expected condition to be true")
        }
    }

}