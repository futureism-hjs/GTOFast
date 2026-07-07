package com.gtofast;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * JNI bridge to gtofast_jvmti native DLL.
 *
 * Mirrors GTOCutCorners' approach:
 *   Layer 1: ClassFileLoadHook patches RecipeModifier.overclocking() dconst_1->dconst_0
 *   Layer 2: RecipeLogic linked list (registered by Mixin)
 *   Layer 3: Native watchdog thread (3s interval, fixes duration>1->1)
 *   Extra:  nativeMassPatch (native JNI recipe data patching)
 *
 * Only used when durationFactor <= 0 (1-tick mode).
 */
public final class NativeAgent {

    static final Logger LOGGER = LogUtils.getLogger();

    private static boolean loaded;
    private static boolean available;

    private NativeAgent() {}

    /**
     * Load DLL, init JVMTI, start watchdog.
     * Called from GTOFast constructor for factor==0.
     */
    static boolean init() {
        if (loaded) return available;
        loaded = true;

        try {
            /* --- Step 1: Extract and load DLL --- */
            String os = System.getProperty("os.name").toLowerCase();
            String arch = System.getProperty("os.arch").toLowerCase();
            String resName;

            if (os.contains("win")) {
                resName = arch.contains("64") ? "native/gtofast_jvmti_x64.dll" : "native/gtofast_jvmti_x86.dll";
            } else if (os.contains("linux")) {
                resName = "native/libgtofast_jvmti_x64.so";
            } else if (os.contains("mac")) {
                resName = arch.contains("aarch64") ? "native/libgtofast_jvmti_arm64.dylib" : "native/libgtofast_jvmti_x64.dylib";
            } else {
                LOGGER.warn("[GTOFast] Unsupported OS: {}", os);
                return false;
            }

            InputStream in = NativeAgent.class.getClassLoader().getResourceAsStream(resName);
            if (in == null) {
                LOGGER.warn("[GTOFast] Native DLL not found in JAR: {}", resName);
                return false;
            }

            Path tmp = Files.createTempDirectory("gtofast");
            String dlName = os.contains("win") ? "gtofast_jvmti.dll"
                         : os.contains("linux") ? "libgtofast_jvmti.so"
                         : "libgtofast_jvmti.dylib";
            Path dllPath = tmp.resolve(dlName);
            Files.copy(in, dllPath, StandardCopyOption.REPLACE_EXISTING);
            in.close();
            dllPath.toFile().deleteOnExit();

            System.load(dllPath.toAbsolutePath().toString());
            LOGGER.info("[GTOFast] JVMTI agent loaded via System.load()");

            /* --- Step 2: Retransform already-loaded RecipeModifier --- */
            nativeInitJVMTI();

            /* --- Step 3: Start native watchdog (corrects RecipeLogic durations live) --- */
            nativeStartWatchdog();
            LOGGER.info("[GTOFast] Native watchdog started");

            available = true;
            return true;

        } catch (UnsatisfiedLinkError e) {
            LOGGER.warn("[GTOFast] JVMTI load failed (UnsatisfiedLinkError): {}", e.getMessage());
            return false;
        } catch (Exception e) {
            LOGGER.warn("[GTOFast] JVMTI load failed: {}", e.getMessage());
            return false;
        }
    }

    static boolean isAvailable() { return available; }

    // ---- JNI Native Methods ----

    /** Retransform already-loaded RecipeModifier to trigger ClassFileLoadHook */
    private static native void nativeInitJVMTI();

    /** Mass-patch all GT recipe definitions to targetDuration via native JNI */
    static native int nativeMassPatch(int targetDuration);

    /** Register a RecipeLogic into the native linked list (called by Mixin) */
    public static native void nativeRegisterRecipeLogic(Object logic);

    /** Unregister a RecipeLogic from the native linked list (called by Mixin) */
    public static native void nativeUnregisterRecipeLogic(Object logic);

    /** Start native watchdog daemon thread */
    private static native void nativeStartWatchdog();

    /** Stop native watchdog daemon thread */
    private static native void nativeStopWatchdog();
}
