package dev.aibuild.script;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Paper loads plugins in their own class loader while worker threads keep the server's context class loader,
 * which cannot see the plugin jar. Rhino's ServiceLoader lookup of its RegExp engine then finds nothing. This
 * reproduces that: the runtime and Rhino are loaded fresh, under a context loader that sees neither.
 */
class RegExpClassLoaderTest {
    @Test void regexWorksWhenContextLoaderCannotSeeRhino() throws Exception {
        List<URL> urls = new ArrayList<>();
        for (String p : System.getProperty("java.class.path").split(File.pathSeparator)) urls.add(new File(p).toURI().toURL());
        ClassLoader platform = ClassLoader.getPlatformClassLoader();
        Thread t = Thread.currentThread();
        ClassLoader saved = t.getContextClassLoader();
        try (URLClassLoader plugin = new URLClassLoader(urls.toArray(URL[]::new), platform)) {
            t.setContextClassLoader(platform);
            Class<?> infoClass = Class.forName("dev.aibuild.model.StubBlockInfo", true, plugin);
            Class<?> blockInfo = Class.forName("dev.aibuild.model.BlockInfo", true, plugin);
            Class<?> runtimeClass = Class.forName("dev.aibuild.script.ScriptRuntime", true, plugin);
            Class<?> budgetClass = Class.forName("dev.aibuild.script.ScriptRuntime$Budget", true, plugin);
            Class<?> modelClass = Class.forName("dev.aibuild.model.VoxelModel", true, plugin);
            Object runtime = runtimeClass.getConstructor(blockInfo).newInstance(infoClass.getConstructor().newInstance());
            Object budget = budgetClass.getMethod("standard").invoke(null);
            Object result = runtimeClass.getMethod("run", String.class, long.class, modelClass, int[].class, budgetClass,
                            java.util.concurrent.atomic.AtomicBoolean.class)
                    .invoke(runtime, "var n = 'oak_planks'.replace(/_planks$/, '_log').match(/oak/g).length;\n"
                            + "box(0,1,0,n,1,0,'stone');", 1L, null, null, budget, null);
            Object error = result.getClass().getMethod("error").invoke(result);
            assertNull(error, () -> describe(error));
        } finally {
            t.setContextClassLoader(saved);
        }
    }

    private static String describe(Object error) {
        try {
            return (String) error.getClass().getMethod("describe").invoke(error);
        } catch (ReflectiveOperationException e) {
            return String.valueOf(error);
        }
    }
}
