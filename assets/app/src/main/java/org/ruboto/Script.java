package org.ruboto;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

public class Script {
    // Replaced, never modified, so readers on other threads need no lock.
    private static volatile String[] scriptsDir = new String[]{"scripts"};

    private final String name;
    private String absolutePath;
    private boolean resolved;

    public static synchronized void addDir(String dir) {
        String[] oldScriptsDir = scriptsDir;
        String[] dirs = new String[oldScriptsDir.length + 1];
        dirs[0] = dir;
        System.arraycopy(oldScriptsDir, 0, dirs, 1, oldScriptsDir.length);
        scriptsDir = dirs;
    }

    public static String toCamelCase(String s) {
        String[] parts = s.replace(".rb", "").split("_");
        for (int i = 0 ; i < parts.length ; i++) {
            if (parts[i].length() == 0) continue;
            parts[i] = parts[i].substring(0,1).toUpperCase() + parts[i].substring(1);
        }
        return java.util.Arrays.toString(parts).replace(", ", "").replaceAll("[\\[\\]]", "");
    }

    public static String toSnakeCase(String s) {
        return s.replaceAll(
            String.format("%s|%s|%s",
                "(?<=[A-Z])(?=[A-Z][a-z0-9])",
                "(?<=[^A-Z])(?=[A-Z])",
                "(?<=[A-Za-z0-9])(?=[^A-Za-z0-9])"
            ),
            "_"
        ).replace("__", "_").toLowerCase();
    }

    public Script(String name) {
        this.name = name;
    }

    public String execute() throws IOException {
        return JRubyAdapter.runScriptlet(getContents()).toString();
    }

    boolean exists() {
        return getAbsolutePath() != null;
    }

    // Resolved once: loading a script asks for it several times.
    String getAbsolutePath() {
        if (!resolved) {
            absolutePath = resolve();
            resolved = true;
        }
        return absolutePath;
    }

    private String resolve() {
        for (String dir : scriptsDir) {
            // jar: and uri: entries are covered by the class loader below.
            if (dir.startsWith("jar:") || dir.startsWith("uri:")) continue;
            String path = dir + "/" + name;
            if (new File(path).exists()) {
                return "file:" + path;
            }
        }
        URL url = Script.class.getClassLoader().getResource(name);
        if (url != null) {
            return url.toString();
        }
        return null;
    }

    public File getFile() {
        for (String dir : scriptsDir) {
            File f = new File(dir, name);
            if (f.exists()) {
                return f;
            }
        }
        return new File(scriptsDir[0], name);
    }
		
    public String getContents() throws IOException {
        String path = getAbsolutePath();
        if (path == null) {
            throw new IOException("Script not found: " + name);
        }
        // Read by hand: InputStream.readAllBytes needs API 33.
        InputStream is = new URL(path).openStream();
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(8192);
            byte[] buffer = new byte[8192];
            for (int n; (n = is.read(buffer)) != -1; ) {
                bytes.write(buffer, 0, n);
            }
            // Ruby source is UTF-8 whatever the platform charset says.
            return bytes.toString("UTF-8");
        } finally {
            is.close();
        }
    }

    public String getName() {
        return name;
    }

}
