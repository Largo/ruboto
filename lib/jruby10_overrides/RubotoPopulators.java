package org.jruby;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;

/**
 * Ruboto: makes RubyModule.loadPopulatorFor find the pregenerated
 * org.jruby.gen.*$POPULATOR classes on Android.
 *
 * loadPopulatorFor only uses a populator if
 * Ruby.getClassLoader().getResource("org/jruby/gen/...$POPULATOR.class") is
 * not null.  An APK has no .class resources (AGP dexes them), so on ART that
 * check always fails and every core class is bound by
 * ReflectiveTypePopulator: getDeclaredMethods, @JRubyMethod annotation
 * parsing and a MethodHandle chain per method.  That is about a quarter of
 * JRuby's boot CPU, and the populators and their $INVOKER classes ship in the
 * dex unused.
 *
 * JRuby10AndroidPatcher retargets that getResource call to
 * getResource(ClassLoader, String) below, which has the same stack shape and
 * probes for the class instead.
 */
public final class RubotoPopulators {
    // loadPopulatorFor only compares the result with null.
    private static final URL FOUND;
    static {
        URL found = null;
        try {
            found = URI.create("file:/ruboto/populator").toURL();
        } catch (MalformedURLException e) {
            // Cannot happen for a file: URL.
        }
        FOUND = found;
    }

    private RubotoPopulators() {
    }

    public static URL getResource(ClassLoader loader, String path) {
        if (!path.endsWith(".class")) return loader.getResource(path);
        String name = path.substring(0, path.length() - ".class".length()).replace('/', '.');
        try {
            // Not initialized here: loadPopulatorFor loads it again with
            // Class.forName(name) and instantiates it.
            Class.forName(name, false, loader);
            return FOUND;
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }
}
