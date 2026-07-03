///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25
//DEPS io.github.vlaaad:ghosttyfx:0.1.173
//DEPS org.openjfx:javafx-controls:26
//DEPS org.openjfx:javafx-graphics:26
//DEPS org.openjfx:javafx-base:26
//DEPS org.openjfx:javafx-web:26
//DEPS org.jetbrains.pty4j:pty4j:0.13.12
//DEPS org.slf4j:slf4j-nop:2.0.13
//DEPS io.smallrye.config:smallrye-config:3.12.4
//SOURCES src/dk/xam/jhostty/*.java
//RUNTIME_OPTIONS --enable-native-access=ALL-UNNAMED --enable-native-access=javafx.graphics -Djavafx.enablePreview=true -Djavafx.suppressPreviewWarning=true

import dk.xam.jhostty.JHostty;

public class jhostty {
    public static void main(String[] args) {
        JHostty.run(args);
    }
}
