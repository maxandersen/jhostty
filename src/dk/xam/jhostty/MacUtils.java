package dk.xam.jhostty;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;

public final class MacUtils {
    private MacUtils() {}

    // Shared objc runtime handles — initialized once
    private static final Arena ARENA = Arena.global();
    private static final MethodHandle CLS, SEL, SEND0, SEND1, SENDV, SENDL;
    static {
        MethodHandle cls = null, sel = null, send0 = null, send1 = null, sendV = null, sendL = null;
        try {
            var linker = Linker.nativeLinker();
            var rt = SymbolLookup.libraryLookup("libobjc.dylib", ARENA);
            cls = linker.downcallHandle(rt.find("objc_getClass").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            sel = linker.downcallHandle(rt.find("sel_registerName").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            var msgSend = rt.find("objc_msgSend").orElseThrow();
            send0 = linker.downcallHandle(msgSend,
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            send1 = linker.downcallHandle(msgSend,
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            sendV = linker.downcallHandle(msgSend,
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            sendL = linker.downcallHandle(msgSend,
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        } catch (Throwable _) {}
        CLS = cls; SEL = sel; SEND0 = send0; SEND1 = send1; SENDV = sendV; SENDL = sendL;
    }

    private static MemorySegment cls(String name) throws Throwable { return (MemorySegment) CLS.invoke(ARENA.allocateFrom(name)); }
    private static MemorySegment sel(String name) throws Throwable { return (MemorySegment) SEL.invoke(ARENA.allocateFrom(name)); }
    private static MemorySegment msg(MemorySegment obj, String selector) throws Throwable { return (MemorySegment) SEND0.invoke(obj, sel(selector)); }
    private static MemorySegment msg(MemorySegment obj, String selector, MemorySegment arg) throws Throwable { return (MemorySegment) SEND1.invoke(obj, sel(selector), arg); }
    private static void msgV(MemorySegment obj, String selector, MemorySegment arg) throws Throwable { SENDV.invoke(obj, sel(selector), arg); }
    private static MemorySegment nsString(String s) throws Throwable { return msg(msg(cls("NSString"), "alloc"), "initWithUTF8String:", ARENA.allocateFrom(s)); }

    public static void setAppName(String name) {
        try {
            var nsApp = msg(cls("NSApplication"), "sharedApplication");
            var appMenu = msg((MemorySegment) SENDL.invoke(msg(nsApp, "mainMenu"), sel("itemAtIndex:"), 0L), "submenu");
            msgV(appMenu, "setTitle:", nsString(name));
        } catch (Throwable _) {}
    }

    public static void setDockIcon(Class<?> resourceClass) {
        try {
            var iconStream = resourceClass.getResourceAsStream("/icon.png");
            if (iconStream == null) return;
            var iconBytes = iconStream.readAllBytes();
            iconStream.close();
            var tmpIcon = Files.createTempFile("jhostty-icon", ".png");
            tmpIcon.toFile().deleteOnExit();
            Files.write(tmpIcon, iconBytes);
            var nsImage = msg(msg(cls("NSImage"), "alloc"), "initWithContentsOfFile:", nsString(tmpIcon.toString()));
            msgV(msg(cls("NSApplication"), "sharedApplication"), "setApplicationIconImage:", nsImage);
        } catch (Throwable _) {}
    }
}
