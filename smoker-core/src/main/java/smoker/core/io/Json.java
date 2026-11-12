package smoker.core.io;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Pretty, stable JSON so scenario files diff cleanly in Git (R-25). */
public final class Json {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private Json() {}

    public static <T> T read(Path file, Class<T> type) throws IOException {
        Reader r = new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8);
        try {
            return GSON.fromJson(r, type);
        } finally {
            r.close();
        }
    }

    public static void write(Path file, Object value) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Writer w = new OutputStreamWriter(Files.newOutputStream(file), StandardCharsets.UTF_8);
        try {
            GSON.toJson(value, w);
            w.write('\n');
        } finally {
            w.close();
        }
    }

    public static String toString(Object value) {
        return GSON.toJson(value);
    }
}
