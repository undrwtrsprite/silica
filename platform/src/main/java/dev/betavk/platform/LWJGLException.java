package dev.betavk.platform;

/** Stand-in for org.lwjgl.LWJGLException (removed in LWJGL 3). */
public class LWJGLException extends Exception {
    public LWJGLException(String message) {
        super(message);
    }
}
