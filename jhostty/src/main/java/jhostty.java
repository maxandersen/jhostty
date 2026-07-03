///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25
//DEPS io.github.vlaaad:ghosttyfx:0.1.173
//DEPS org.openjfx:javafx-controls:26
//DEPS org.openjfx:javafx-graphics:26
//DEPS org.openjfx:javafx-base:26
//DEPS org.jetbrains.pty4j:pty4j:0.13.12
//DEPS org.slf4j:slf4j-nop:2.0.13
//DEPS io.smallrye.config:smallrye-config:3.12.4
//DEPS com.fasterxml.jackson.core:jackson-databind:2.18.2
//SOURCES src/dk/xam/jhostty/JHostty.java
//SOURCES src/dk/xam/jhostty/FontManager.java
//SOURCES src/dk/xam/jhostty/LayoutCodec.java
//SOURCES src/dk/xam/jhostty/MacUtils.java
//SOURCES src/dk/xam/jhostty/PtyTerminal.java
//SOURCES src/dk/xam/jhostty/ShellDetection.java
//SOURCES src/dk/xam/jhostty/SplitWorkspace.java
//SOURCES src/dk/xam/jhostty/Themes.java
//SOURCES src/dk/xam/jhostty/ZmxSession.java
//SOURCES src/dk/xam/themes/ColorUtil.java
//SOURCES src/dk/xam/themes/TerminalColorScheme.java
//SOURCES src/dk/xam/themes/ThemeRegistry.java
//FILES themes/builtin-themes.json=themes/builtin-themes.json
//RUNTIME_OPTIONS --enable-native-access=ALL-UNNAMED --enable-native-access=javafx.graphics -Djavafx.enablePreview=true -Djavafx.suppressPreviewWarning=true
//COMPILE_OPTIONS -Xlint:deprecation -Xlint:unchecked

