package dk.xam.jhostty;

import java.lang.foreign.*;

public final class MacUtils {
    private MacUtils() {}

    public static void setAppName(String name) {
        try {
            var linker = Linker.nativeLinker();
            var rt = SymbolLookup.libraryLookup("libobjc.dylib", Arena.global());
            var arena = Arena.global();
            var cls = linker.downcallHandle(rt.find("objc_getClass").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            var sel = linker.downcallHandle(rt.find("sel_registerName").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            var send0 = linker.downcallHandle(rt.find("objc_msgSend").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            var send1 = linker.downcallHandle(rt.find("objc_msgSend").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            var sendV = linker.downcallHandle(rt.find("objc_msgSend").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            var sendL = linker.downcallHandle(rt.find("objc_msgSend").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
            var nsStr = (MemorySegment) send1.invoke(
                    (MemorySegment) send0.invoke((MemorySegment) cls.invoke(arena.allocateFrom("NSString")),
                            (MemorySegment) sel.invoke(arena.allocateFrom("alloc"))),
                    (MemorySegment) sel.invoke(arena.allocateFrom("initWithUTF8String:")),
                    arena.allocateFrom(name));
            var nsApp = (MemorySegment) send0.invoke(
                    (MemorySegment) cls.invoke(arena.allocateFrom("NSApplication")),
                    (MemorySegment) sel.invoke(arena.allocateFrom("sharedApplication")));
            var mainMenu = (MemorySegment) send0.invoke(nsApp, (MemorySegment) sel.invoke(arena.allocateFrom("mainMenu")));
            var appMenuItem = (MemorySegment) sendL.invoke(mainMenu, (MemorySegment) sel.invoke(arena.allocateFrom("itemAtIndex:")), 0L);
            var appMenu = (MemorySegment) send0.invoke(appMenuItem, (MemorySegment) sel.invoke(arena.allocateFrom("submenu")));
            sendV.invoke(appMenu, (MemorySegment) sel.invoke(arena.allocateFrom("setTitle:")), nsStr);
        } catch (Throwable _) {}
    }
}
