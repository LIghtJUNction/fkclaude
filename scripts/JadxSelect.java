import jadx.api.DecompilationMode;
import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Bounded alternative to decompiling 37,884 classes at once. Run with the JADX all.jar classpath. */
public final class JadxSelect {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) throw new IllegalArgumentException("APK OUTPUT_DIRECTORY RAW_CLASS...");
        JadxArgs config = new JadxArgs();
        config.setInputFiles(List.of(new File(args[0])));
        config.setThreadsCount(1);
        config.setSkipResources(true);
        config.setDecompilationMode(DecompilationMode.SIMPLE);
        Set<String> wanted = new HashSet<>(Arrays.asList(args).subList(2, args.length));
        Path out = Path.of(args[1]);
        Files.createDirectories(out);
        try (JadxDecompiler jadx = new JadxDecompiler(config)) {
            jadx.load();
            for (var cls : jadx.getClasses()) if (wanted.remove(cls.getRawName())) {
                Files.writeString(out.resolve(cls.getRawName() + ".java"), cls.getCode());
                System.out.println("Saved " + cls.getRawName());
            }
        }
        if (!wanted.isEmpty()) throw new IllegalArgumentException("Classes not found: " + wanted);
    }
}
