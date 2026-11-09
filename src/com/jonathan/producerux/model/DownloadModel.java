package com.jonathan.producerux.model;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class DownloadModel {

    public static final int QUALITY_ORIGINAL = 0;
    public static final int QUALITY_1080 = 1;
    public static final int QUALITY_720 = 2;

    private static final long UPDATE_INTERVAL = 6L * 60L * 60L * 1000L;
    private static final long FORCE_MIN_GAP = 10L * 60L * 1000L;
    private static final long MIN_TOOL_SIZE = 1_000_000L;

    private static final double TARGET_LUFS = -9.0;
    private static final double MAX_GAIN_DB = 8.0;
    private static final double MIN_GAIN_DB = 0.3;
    private static final String PEAK_LIMIT = "0.89";

    private static final Pattern PERCENT = Pattern.compile("(\\d+(?:\\.\\d+)?)%");
    private static final Pattern DOMAIN = Pattern.compile("^[\\w-]+(\\.[\\w-]+)+(/\\S*)?$");
    private static final Pattern PROCESS_ERROR = Pattern.compile("error=(\\d+)");
    private static final Object LOCK = new Object();

    private static Path ytDlp;
    private static Path ffmpeg;
    private static boolean prepared = false;

    public interface Listener {
        void onProgress(int percent);
        void onSuccess(String message);
        void onWarning(String title, String message);
        void onError(String title, String message);
    }

    public static class DownloadException extends Exception {

        private static final long serialVersionUID = 1L;

        private final String title;
        private final boolean retryable;

        public DownloadException(String title, String message, boolean retryable) {
            super(message);
            this.title = title;
            this.retryable = retryable;
        }

        public String getTitle() {
            return title;
        }

        public boolean isRetryable() {
            return retryable;
        }
    }

    private record RunResult(int code, String output) {
    }

    public static String normalizeLink(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty() || text.matches(".*\\s.*")) {
            return null;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return text;
        }
        if (DOMAIN.matcher(text).matches()) {
            return "https://" + text;
        }
        return null;
    }

    public static String shorten(String text, int max) {
        if (text == null) {
            return "";
        }
        String t = text.trim();
        return t.length() > max ? t.substring(0, max) + "..." : t;
    }

    private static Path dataDir() {
        String base = System.getenv("LOCALAPPDATA");
        if (base == null || base.isEmpty()) {
            base = System.getProperty("user.home");
        }
        return Path.of(base, "producerUX", "bin");
    }

    private static List<Path> searchDirs() {
        List<Path> bases = new ArrayList<>();
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null) {
            Path root = Path.of(appPath).getParent();
            if (root != null) {
                bases.add(root.resolve("tools"));
                bases.add(root);
                bases.add(root.resolve("app").resolve("tools"));
                bases.add(root.resolve("app"));
            }
        }
        bases.add(Path.of(System.getProperty("user.dir"), "tools"));
        bases.add(Path.of(System.getProperty("user.dir"), "packaging", "tools"));
        return bases;
    }

    private static Path findTool(String exe) {
        for (Path base : searchDirs()) {
            Path candidate = base.resolve(exe);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean usable(Path tool) {
        return tool != null && Files.isRegularFile(tool) && tool.toFile().length() > MIN_TOOL_SIZE;
    }

    private static DownloadException missingTools(Path bundledYtDlp, Path bundledFfmpeg) {
        StringBuilder text = new StringBuilder();
        if (!usable(bundledYtDlp)) {
            text.append(bundledYtDlp == null
                    ? "No se encontró yt-dlp.exe. "
                    : "El archivo yt-dlp.exe está incompleto o dañado (" + bundledYtDlp + "). ");
        }
        if (!usable(bundledFfmpeg)) {
            text.append(bundledFfmpeg == null
                    ? "No se encontró ffmpeg.exe. "
                    : "El archivo ffmpeg.exe está incompleto o dañado (" + bundledFfmpeg + "). ");
        }
        text.append("\n\nLa app los necesita para descargar y convertir. Colócalos en la carpeta \"tools\" junto al programa (o en la raíz del proyecto si la ejecutas desde NetBeans).")
                .append("\n\nCarpetas revisadas:");
        for (Path dir : searchDirs()) {
            text.append("\n").append(dir);
        }
        return new DownloadException("Faltan archivos de la aplicación", text.toString(), false);
    }

    private static Path localCopy(Path bundled) {
        try {
            Path dir = dataDir();
            Files.createDirectories(dir);
            Path local = dir.resolve("yt-dlp.exe");
            if (!usable(local)) {
                Files.copy(bundled, local, StandardCopyOption.REPLACE_EXISTING);
            }
            return usable(local) ? local : bundled;
        } catch (IOException e) {
            return bundled;
        }
    }

    public static void prepare() throws DownloadException {
        synchronized (DownloadModel.class) {
            if (!(prepared && usable(ytDlp) && usable(ffmpeg))) {
                prepared = false;
                Path bundledYtDlp = findTool("yt-dlp.exe");
                Path bundledFfmpeg = findTool("ffmpeg.exe");
                if (!usable(bundledYtDlp) || !usable(bundledFfmpeg)) {
                    throw missingTools(bundledYtDlp, bundledFfmpeg);
                }
                ytDlp = localCopy(bundledYtDlp);
                ffmpeg = bundledFfmpeg;
                prepared = true;
            }
        }
        updateYtDlp(false);
    }

    public static void prepareInBackground() {
        Thread worker = new Thread(() -> {
            try {
                prepare();
            } catch (Exception ignored) {
            }
        });
        worker.setDaemon(true);
        worker.start();
    }

    public static void updateYtDlp(boolean force) {
        synchronized (LOCK) {
            try {
                Path stamp = dataDir().resolve("update.stamp");
                long now = System.currentTimeMillis();
                long gap = force ? FORCE_MIN_GAP : UPDATE_INTERVAL;
                if (Files.exists(stamp) && now - Files.getLastModifiedTime(stamp).toMillis() < gap) {
                    return;
                }
                RunResult result = run(List.of(ytDlp.toString(), "--update-to", "nightly"), null);
                if (result.code() == 0) {
                    Files.createDirectories(stamp.getParent());
                    Files.writeString(stamp, String.valueOf(now));
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static RunResult run(List<String> command, Consumer<String> onLine) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("PYTHONUTF8", "1");
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        builder.redirectErrorStream(true);

        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
                if (onLine != null) {
                    onLine.accept(line);
                }
            }
        }
        int code = process.waitFor();
        return new RunResult(code, output.toString());
    }

    private static String formatFor(int quality) {
        switch (quality) {
            case QUALITY_1080:
                return "bv*[height<=1080]+ba/b[height<=1080]/b";
            case QUALITY_720:
                return "bv*[height<=720]+ba/b[height<=720]/b";
            default:
                return "bv*+ba/b";
        }
    }

    public void download(String url, boolean audio, int quality, String customName, Listener listener) {
        Thread worker = new Thread(() -> {
            Path temp = null;
            try {
                prepare();
                temp = createTempFolder();

                Path result = downloadWithRetry(temp, url, audio, quality, customName, listener);
                if (result == null) {
                    throw new DownloadException("No se generó ningún archivo",
                            "yt-dlp terminó sin errores pero no dejó ningún archivo descargado. "
                            + "Puede pasar si el enlace es una lista o una página sin un video descargable. Prueba con el enlace directo de un video.",
                            false);
                }

                StringBuilder note = new StringBuilder();
                StringBuilder problem = new StringBuilder();
                result = adjustVolume(result, audio, note, problem);

                Path saved = saveToDownloads(result);
                listener.onSuccess(saved + note.toString());

                if (problem.length() > 0) {
                    listener.onWarning("Archivo guardado sin ajuste de volumen",
                            "La descarga terminó bien y el archivo está en:\n" + saved
                            + "\n\nPero no se pudo subir el volumen. Por qué: " + problem);
                }
            } catch (DownloadException e) {
                listener.onError(e.getTitle(), e.getMessage());
            } catch (Exception e) {
                listener.onError("Error inesperado",
                        "Ocurrió un error que la app no esperaba y detuvo la descarga.\n\nDetalle técnico:\n"
                        + shorten(e.getMessage() == null ? e.toString() : e.getMessage(), 500));
            } finally {
                deleteRecursive(temp);
            }
        });
        worker.setDaemon(true);
        worker.start();
    }

    private Path createTempFolder() throws DownloadException {
        try {
            return Files.createTempDirectory("producerux_");
        } catch (IOException e) {
            throw new DownloadException("No se pudo crear la carpeta temporal",
                    "La app necesita una carpeta temporal para trabajar y Windows no permitió crearla. "
                    + "Puede deberse a que el disco está lleno o a que un antivirus bloquea la carpeta Temp.\n\nDetalle técnico:\n"
                    + shorten(e.toString(), 400),
                    false);
        }
    }

    private Path downloadWithRetry(Path temp, String url, boolean audio, int quality, String customName, Listener listener) throws DownloadException {
        try {
            return runDownload(temp, url, audio, quality, customName, listener);
        } catch (DownloadException first) {
            if (!first.isRetryable()) {
                throw first;
            }
            updateYtDlp(true);
            listener.onProgress(0);
            try {
                return runDownload(temp, url, audio, quality, customName, listener);
            } catch (DownloadException second) {
                throw new DownloadException(second.getTitle(),
                        second.getMessage() + "\n\nLa app ya intentó actualizar yt-dlp y reintentó automáticamente, sin éxito.",
                        false);
            }
        }
    }

    private static String outputTemplate(String customName) throws DownloadException {
        if (customName == null || customName.trim().isEmpty()) {
            return "%(title)s";
        }

        String clean = customName.trim().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        if (clean.length() > 150) {
            clean = clean.substring(0, 150);
        }
        clean = clean.replaceAll("[. ]+$", "");

        if (clean.isEmpty()) {
            throw new DownloadException("Nombre no válido",
                    "El nombre que escribiste solo tiene puntos o espacios, y Windows no permite archivos con ese nombre. Escribe un nombre con letras o números.",
                    false);
        }
        if (clean.matches("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?")) {
            clean = "_" + clean;
        }
        return clean.replace("%", "%%");
    }

    private Path runDownload(Path temp, String url, boolean audio, int quality, String customName, Listener listener) throws DownloadException {
        String template = outputTemplate(customName);

        List<String> command = new ArrayList<>();
        command.add(ytDlp.toString());
        command.addAll(Arrays.asList(
                "--ffmpeg-location", ffmpeg.getParent().toString(),
                "--newline",
                "--no-playlist",
                "--windows-filenames",
                "--trim-filenames", "150",
                "-o", temp.toString() + File.separator + template + ".%(ext)s"));

        if (audio) {
            command.addAll(Arrays.asList("-f", "ba/b", "-x", "--audio-format", "mp3", "--audio-quality", "0"));
        } else {
            command.addAll(Arrays.asList("-f", formatFor(quality), "--merge-output-format", "mp4"));
        }

        command.add("--");
        command.add(url);

        RunResult result;
        try {
            synchronized (LOCK) {
                result = run(command, line -> {
                    if (line.startsWith("[download]")) {
                        Matcher m = PERCENT.matcher(line);
                        if (m.find()) {
                            listener.onProgress((int) Math.round(Double.parseDouble(m.group(1))));
                        }
                    }
                });
            }
        } catch (IOException e) {
            throw startFailure(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownloadException("Descarga interrumpida", "La descarga se detuvo antes de terminar porque el proceso fue interrumpido.", false);
        }

        if (result.code() != 0) {
            throw classify(result.output());
        }

        try {
            return pickResult(temp, audio);
        } catch (IOException e) {
            throw new DownloadException("No se pudo leer el archivo descargado",
                    "yt-dlp terminó, pero la app no pudo leer la carpeta temporal donde quedó el archivo.\n\nDetalle técnico:\n"
                    + shorten(e.toString(), 400),
                    false);
        }
    }

    private static DownloadException startFailure(IOException e) {
        String raw = e.getMessage() == null ? e.toString() : e.getMessage();
        String detail = "\n\nDetalle técnico:\n" + shorten(raw, 500);

        int code = -1;
        Matcher m = PROCESS_ERROR.matcher(raw);
        if (m.find()) {
            code = Integer.parseInt(m.group(1));
        }

        switch (code) {
            case 2:
                return new DownloadException("No se encontró yt-dlp.exe",
                        "Windows no encuentra el archivo yt-dlp.exe que la app intentó ejecutar. Pudo ser borrado o movido (a veces por un antivirus). Cierra y vuelve a abrir la app para que lo restaure." + detail,
                        false);
            case 5:
                return new DownloadException("Windows bloqueó yt-dlp.exe",
                        "Windows negó el permiso para ejecutar yt-dlp.exe. Normalmente lo causa un antivirus o una política de seguridad. Agrega la carpeta de la app y %LOCALAPPDATA%\\producerUX a las excepciones del antivirus." + detail,
                        false);
            case 193:
                return new DownloadException("yt-dlp.exe dañado o incompatible",
                        "Windows dice que yt-dlp.exe no es una aplicación válida. El archivo está incompleto o es de otra arquitectura. Vuelve a descargar yt-dlp.exe (versión Windows 64 bits) y reemplázalo en la carpeta tools." + detail,
                        false);
            case 225:
                return new DownloadException("El antivirus bloqueó yt-dlp.exe",
                        "Windows detectó yt-dlp.exe como un archivo no deseado y no lo dejó ejecutar. Agrégalo a las excepciones del antivirus o restáuralo desde la cuarentena." + detail,
                        false);
            default:
                return new DownloadException("No se pudo iniciar yt-dlp",
                        "La app intentó ejecutar yt-dlp.exe y Windows no pudo iniciarlo." + detail,
                        false);
        }
    }

    private static boolean has(String text, String... keys) {
        for (String key : keys) {
            if (text.contains(key)) {
                return true;
            }
        }
        return false;
    }

    private static DownloadException classify(String output) {
        String errors = errorText(output);
        String lower = errors.toLowerCase(Locale.ROOT);
        String all = output.toLowerCase(Locale.ROOT);
        String detail = "\n\nDetalle técnico:\n" + shorten(errors, 500);

        if (has(all, "precondition check failed", "http error 400")) {
            return new DownloadException("La plataforma rechazó la solicitud",
                    "La plataforma respondió con error 400 (Precondition check failed). Casi siempre pasa cuando YouTube cambia su sistema y la versión de yt-dlp ya no coincide con él."
                    + "\n\nDetalle técnico:\n" + shorten(tail(output), 500),
                    true);
        }
        if (has(lower, "unsupported url")) {
            return new DownloadException("Enlace no compatible",
                    "Esa dirección no es un video que la app pueda descargar. Suele pasar con historias destacadas de Instagram, páginas de perfil o enlaces que no apuntan a un video. Copia el enlace directo del video o publicación." + detail,
                    false);
        }
        if (has(lower, "private video", "video is private", "this video is private")) {
            return new DownloadException("Video privado",
                    "El video es privado: solo su dueño o personas autorizadas pueden verlo, y la app no inicia sesión en ninguna cuenta." + detail,
                    false);
        }
        if (has(lower, "confirm your age", "age-restricted", "age restricted", "inappropriate for some users")) {
            return new DownloadException("Video con restricción de edad",
                    "La plataforma exige iniciar sesión para confirmar la edad, y la app no usa ninguna cuenta." + detail,
                    false);
        }
        if (has(lower, "not a bot")) {
            return new DownloadException("La plataforma pidió verificación",
                    "YouTube detectó muchas solicitudes desde tu red o conexión y pidió confirmar que no eres un robot. Cambia de red (por ejemplo de Wi-Fi a datos móviles) o espera un rato antes de volver a intentar." + detail,
                    false);
        }
        if (has(lower, "please sign in", "sign in", "log in", "login required", "logged-in", "logged in", "cookies")) {
            return new DownloadException("El contenido exige iniciar sesión",
                    "La plataforma solo muestra este video a usuarios con sesión iniciada, y la app no usa ninguna cuenta. Pasa en Instagram, Facebook y algunos videos de YouTube." + detail,
                    false);
        }
        if (has(lower, "members-only", "members only", "join this channel", "premium", "rental", "purchase")) {
            return new DownloadException("Contenido de pago o solo para miembros",
                    "Este video requiere una suscripción, membresía o compra, y la app no puede acceder a contenido de pago." + detail,
                    false);
        }
        if (has(lower, "not available in your country", "geo restrict", "geo-restrict", "in your country")) {
            return new DownloadException("Video bloqueado en tu país",
                    "El dueño del video lo restringió para algunos países y el tuyo está entre ellos." + detail,
                    false);
        }
        if (has(lower, "copyright")) {
            return new DownloadException("Video bloqueado por derechos de autor",
                    "La plataforma bloqueó este video por una reclamación de derechos de autor." + detail,
                    false);
        }
        if (has(lower, "drm")) {
            return new DownloadException("Video protegido (DRM)",
                    "Este video está cifrado con protección anticopia (DRM) y no se puede descargar." + detail,
                    false);
        }
        if (has(lower, "live event will begin", "premieres in", "will begin in", "this live event", "is live")) {
            return new DownloadException("Transmisión en vivo o estreno todavía no disponible",
                    "El enlace es de una transmisión en vivo o un estreno que aún no termina o no empieza. Intenta de nuevo cuando ya esté publicado como video normal." + detail,
                    false);
        }
        if (has(lower, "video unavailable", "this video is unavailable", "has been removed", "no longer available", "does not exist", "http error 404", "been deleted", "account has been terminated")) {
            return new DownloadException("Video no disponible",
                    "El video ya no existe o no se puede ver: fue eliminado, la cuenta fue cerrada o el enlace está mal copiado. Abre el enlace en tu navegador para comprobarlo." + detail,
                    false);
        }
        if (has(lower, "http error 429", "too many requests")) {
            return new DownloadException("Demasiadas solicitudes",
                    "La plataforma limitó temporalmente tu conexión por hacer muchas descargas seguidas. Espera un rato o cambia de red antes de volver a intentar." + detail,
                    false);
        }
        if (has(lower, "http error 403", "forbidden")) {
            return new DownloadException("Acceso denegado por la plataforma",
                    "La plataforma negó el acceso al video (error 403). Puede ser un bloqueo temporal de tu red o un cambio en su sistema." + detail,
                    true);
        }
        if (has(lower, "http error 500", "http error 502", "http error 503", "http error 504")) {
            return new DownloadException("Error temporal de la plataforma",
                    "El servidor de la plataforma tuvo un fallo (error 5xx) y no pudo entregar el video. Normalmente se resuelve esperando unos minutos." + detail,
                    true);
        }
        if (has(lower, "getaddrinfo failed", "name or service not known", "temporary failure in name resolution", "network is unreachable",
                "timed out", "connection reset", "connection refused", "unable to connect", "urlopen error", "winerror 10060", "winerror 10061", "winerror 11001")) {
            return new DownloadException("Sin conexión a internet",
                    "La app no pudo conectarse con la plataforma. Revisa que tengas internet, que no haya una VPN o firewall bloqueando la conexión, y vuelve a intentar." + detail,
                    false);
        }
        if (has(lower, "requested format is not available")) {
            return new DownloadException("Calidad no disponible",
                    "El video no ofrece la calidad que elegiste. Prueba con otra calidad, por ejemplo Original." + detail,
                    false);
        }
        if (has(lower, "ffmpeg", "ffprobe")) {
            return new DownloadException("Falló la conversión con ffmpeg",
                    "yt-dlp descargó el video, pero ffmpeg no pudo unir o convertir el audio y el video. Revisa que ffmpeg.exe esté completo en la carpeta tools." + detail,
                    false);
        }
        if (has(lower, "no space left", "not enough space", "disk full")) {
            return new DownloadException("Sin espacio en disco",
                    "El disco donde se guarda el archivo no tiene espacio libre suficiente. Libera espacio y vuelve a intentar." + detail,
                    false);
        }
        if (has(lower, "permission denied", "access is denied", "winerror 5")) {
            return new DownloadException("Sin permiso para guardar el archivo",
                    "Windows no dejó escribir el archivo. Puede ser un antivirus, una carpeta protegida o un archivo abierto en otro programa." + detail,
                    false);
        }
        if (has(lower, "unable to extract", "unable to download json", "unable to download api", "extractorerror", "no video formats found", "no video could be found", "cannot parse")) {
            return new DownloadException("No se pudo leer el video",
                    "yt-dlp no logró interpretar la página del video. Normalmente pasa porque la plataforma cambió algo y yt-dlp todavía no se ha adaptado." + detail,
                    true);
        }
        return new DownloadException("No se pudo descargar el video",
                "La descarga falló por un motivo que la app no reconoce. Revisa que el enlace abra en tu navegador y vuelve a intentar." + detail,
                true);
    }

    private static String errorText(String output) {
        StringBuilder text = new StringBuilder();
        for (String line : output.split("\\R")) {
            if (line.startsWith("ERROR")) {
                text.append(line).append('\n');
            }
        }
        return text.length() > 0 ? text.toString().trim() : tail(output);
    }

    private static String tail(String text) {
        String t = text == null ? "" : text.trim();
        return t.length() > 500 ? t.substring(t.length() - 500) : t;
    }

    private static String grab(String text, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"(-?[0-9.]+)\"").matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private RunResult encode(Path input, Path output, String filter, boolean audio) throws IOException, InterruptedException {
        List<String> args = new ArrayList<>(List.of(
                ffmpeg.toString(), "-y", "-hide_banner", "-nostats", "-loglevel", "error",
                "-i", input.toString()));

        if (audio) {
            args.addAll(Arrays.asList(
                    "-map", "0:a:0", "-map_metadata", "0",
                    "-af", filter, "-ar", "44100",
                    "-c:a", "libmp3lame", "-b:a", "256k"));
        } else {
            args.addAll(Arrays.asList(
                    "-map", "0:v:0?", "-map", "0:a:0?",
                    "-c:v", "copy",
                    "-af", filter, "-ar", "48000",
                    "-c:a", "aac", "-b:a", "256k",
                    "-movflags", "+faststart"));
        }
        args.add(output.toString());

        return run(args, null);
    }

    private boolean succeeded(RunResult result, Path output) {
        return result.code() == 0 && output.toFile().exists() && output.toFile().length() > 0;
    }

    private Path adjustVolume(Path input, boolean audio, StringBuilder note, StringBuilder problem) {
        String name = input.getFileName().toString().toLowerCase(Locale.ROOT);
        if (audio && !name.endsWith(".mp3")) {
            problem.append("el archivo no quedó en formato MP3 (").append(name).append(") y solo se ajusta el volumen de archivos MP3.");
            return input;
        }
        if (!audio && !name.endsWith(".mp4")) {
            problem.append("el video no quedó en formato MP4 (").append(name).append(") y solo se ajusta el volumen de archivos MP4.");
            return input;
        }

        Path output = input.resolveSibling("boost_" + input.getFileName());
        try {
            List<String> measureCommand = List.of(
                    ffmpeg.toString(), "-hide_banner", "-nostats",
                    "-i", input.toString(),
                    "-map", "0:a:0",
                    "-af", "loudnorm=I=-14:TP=-1.5:LRA=11:print_format=json",
                    "-f", "null", "-");

            RunResult measured = run(measureCommand, null);
            if (measured.code() != 0) {
                if (measured.output().toLowerCase(Locale.ROOT).contains("matches no streams")) {
                    note.append("\nEl video no tiene pista de audio, no se ajustó el volumen");
                } else {
                    problem.append("ffmpeg no pudo analizar el volumen del archivo (código ")
                            .append(measured.code()).append(").\nDetalle técnico: ").append(tail(measured.output()));
                }
                return input;
            }

            String measuredLoudness = grab(measured.output(), "input_i");
            String measuredPeak = grab(measured.output(), "input_tp");
            if (measuredLoudness == null || measuredPeak == null) {
                problem.append("ffmpeg no devolvió una medición de volumen legible. Puede ser que el audio esté en silencio total.\nDetalle técnico: ")
                        .append(tail(measured.output()));
                return input;
            }

            double loudness = Double.parseDouble(measuredLoudness);
            double peak = Double.parseDouble(measuredPeak);
            double gain = Math.min(MAX_GAIN_DB, TARGET_LUFS - loudness);

            if (gain < MIN_GAIN_DB) {
                note.append("\nVolumen: ").append(measuredLoudness).append(" LUFS (ya estaba fuerte, sin cambios)");
                return input;
            }

            String gainText = String.format(Locale.US, "%.2f", gain);
            String limited = "volume=" + gainText + "dB,alimiter=limit=" + PEAK_LIMIT
                    + ":attack=5:release=80:level=0";

            RunResult encoded = encode(input, output, limited, audio);

            if (!succeeded(encoded, output)) {
                Files.deleteIfExists(output);
                double safe = Math.min(gain, -1.0 - peak);
                if (safe >= MIN_GAIN_DB) {
                    gainText = String.format(Locale.US, "%.2f", safe);
                    encoded = encode(input, output, "volume=" + gainText + "dB", audio);
                }
            }

            if (succeeded(encoded, output)) {
                note.append("\nVolumen: ").append(measuredLoudness).append(" LUFS, subido ")
                        .append(gainText).append(" dB");
                Files.move(output, input, StandardCopyOption.REPLACE_EXISTING);
                return input;
            }

            problem.append("ffmpeg falló al aplicar el volumen (código ").append(encoded.code())
                    .append(").\nDetalle técnico: ").append(tail(encoded.output()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            problem.append("el proceso fue interrumpido.");
        } catch (Exception e) {
            problem.append("ffmpeg no se pudo ejecutar.\nDetalle técnico: ")
                    .append(shorten(e.getMessage() == null ? e.toString() : e.getMessage(), 400));
        }

        try {
            Files.deleteIfExists(output);
        } catch (IOException ignored) {
        }
        return input;
    }

    private Path pickResult(Path dir, boolean audio) throws IOException {
        List<Path> files;
        try (Stream<Path> stream = Files.list(dir)) {
            files = stream.filter(Files::isRegularFile).toList();
        }

        String wanted = audio ? ".mp3" : ".mp4";
        for (Path f : files) {
            if (f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(wanted)) {
                return f;
            }
        }
        for (Path f : files) {
            String n = f.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!n.endsWith(".part") && !n.endsWith(".ytdl")) {
                return f;
            }
        }
        return null;
    }

    private Path saveToDownloads(Path file) throws DownloadException {
        Path dir = Path.of(System.getProperty("user.home"), "Downloads", "producerUX");
        try {
            Files.createDirectories(dir);

            String fileName = file.getFileName().toString().replaceFirst("^boost_", "");
            String base = fileName;
            String ext = "";
            int dot = fileName.lastIndexOf('.');
            if (dot > 0) {
                base = fileName.substring(0, dot);
                ext = fileName.substring(dot);
            }

            Path target = dir.resolve(fileName);
            int n = 1;
            while (Files.exists(target)) {
                target = dir.resolve(base + " (" + n + ")" + ext);
                n++;
            }

            Files.move(file, target);
            return target;
        } catch (IOException e) {
            throw new DownloadException("No se pudo guardar el archivo",
                    "La descarga terminó, pero Windows no permitió guardar el archivo en:\n" + dir
                    + "\n\nPuede deberse a que no hay espacio en el disco, a que la carpeta no tiene permisos o a que un antivirus la bloquea.\n\nDetalle técnico:\n"
                    + shorten(e.toString(), 400),
                    false);
        }
    }

    private static void deleteRecursive(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
