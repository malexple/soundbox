package ru.mcs;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Консольный режим SoundBox.
 *
 * Использование:
 *   soundbox hide    -i audio.wav -t "текст" -p пароль [-o out.wav]
 *   soundbox extract -i audio.wav -p пароль [-o out.txt]
 *   soundbox help
 *   soundbox version
 *
 * Коды возврата:
 *   0 — успех
 *   1 — ошибка выполнения (неверный пароль, недостаток места, битый файл…)
 *   2 — ошибка использования (не хватает аргументов и т. п.)
 */
public final class Cli {

    private static final String VERSION = "0.2.0";
    private static final String ENV_PASSWORD = "SOUNDBOX_PASSWORD";

    private Cli() {
    }

    public static int run(String[] args) {
        if (args.length == 0) {
            printHelp();
            return 2;
        }

        try {
            String cmd = args[0];

            return switch (cmd) {
                case "hide" -> cmdHide(parse(args, 1));
                case "extract" -> cmdExtract(parse(args, 1));
                case "help", "-h", "--help" -> {
                    printHelp();
                    yield 0;
                }
                case "version", "-v", "--version" -> {
                    System.out.println("SoundBox " + VERSION);
                    yield 0;
                }
                default -> {
                    System.err.println("Неизвестная команда: " + cmd);
                    System.err.println("Подсказка: soundbox help");
                    yield 2;
                }
            };
        } catch (UsageException e) {
            System.err.println("Ошибка: " + e.getMessage());
            System.err.println("Подсказка: soundbox help");
            return 2;
        } catch (Exception e) {
            System.err.println("Ошибка: " + e.getMessage());
            return 1;
        }
    }

    // ---------- Команды ----------

    private static int cmdHide(Map<String, String> opts) throws Exception {
        File input = requireFile(opts);
        char[] password = resolvePassword(opts);
        String text = resolveText(opts);

        try {
            AudioEngine.PcmAudio pcm = AudioEngine.loadPcm(input);
            byte[] stegoData = AudioEngine.hideText(pcm.data(), text, password);

            File output = resolveOutput(opts, input);
            AudioEngine.writeWav(output, pcm.format(), stegoData);

            System.out.println("Сохранено: " + output.getAbsolutePath());
            return 0;
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static int cmdExtract(Map<String, String> opts) throws Exception {
        File input = requireFile(opts);
        char[] password = resolvePassword(opts);

        String text;
        try {
            AudioEngine.PcmAudio pcm = AudioEngine.loadPcm(input);
            text = AudioEngine.extractText(pcm.data(), password);
        } finally {
            Arrays.fill(password, '\0');
        }

        String outPath = getOpt(opts, "output", "o");
        if (outPath != null) {
            Files.writeString(Path.of(outPath), text, StandardCharsets.UTF_8);
            System.out.println("Текст сохранён: " + outPath);
        } else {
            System.out.println(text);
        }

        return 0;
    }

    // ---------- Разбор аргументов ----------

    private static Map<String, String> parse(String[] args, int from) {
        Map<String, String> opts = new LinkedHashMap<>();

        for (int i = from; i < args.length; i++) {
            String a = args[i];

            if (!a.startsWith("-")) {
                throw new UsageException("Неожиданный аргумент: " + a);
            }

            String key;
            String value;

            int eq = a.indexOf('=');
            if (eq > 0) {
                key = stripDashes(a.substring(0, eq));
                value = a.substring(eq + 1);
            } else {
                key = stripDashes(a);

                boolean nextIsValue = i + 1 < args.length
                        && (!args[i + 1].startsWith("-") || args[i + 1].equals("-"));

                value = nextIsValue ? args[++i] : "true";
            }

            opts.put(key, value);
        }

        return opts;
    }

    private static String stripDashes(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == '-') {
            i++;
        }
        return s.substring(i);
    }

    private static String getOpt(Map<String, String> opts, String... keys) {
        for (String k : keys) {
            if (opts.containsKey(k)) {
                return opts.get(k);
            }
        }
        return null;
    }

    // ---------- Помощники ----------

    private static File requireFile(Map<String, String> opts) {
        String path = getOpt(opts, "input", "i");
        if (path == null) {
            throw new UsageException("Не указан входной файл (--input)");
        }

        File f = new File(path);
        if (!f.exists()) {
            throw new UsageException("Файл не найден: " + path);
        }

        return f;
    }

    private static File resolveOutput(Map<String, String> opts, File input) {
        String path = getOpt(opts, "output", "o");
        return path != null ? new File(path) : AudioEngine.buildOutputFile(input);
    }

    private static String resolveText(Map<String, String> opts) throws Exception {
        String inline = getOpt(opts, "text", "t");
        String file = getOpt(opts, "text-file");

        if (inline != null && file != null) {
            throw new UsageException("Укажите только одно из: --text или --text-file");
        }

        if (file != null) {
            return Files.readString(Path.of(file), StandardCharsets.UTF_8);
        }

        if (inline != null) {
            if ("-".equals(inline)) {
                return new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
            }
            return inline;
        }

        throw new UsageException("Не указан текст: используйте --text или --text-file");
    }

    private static char[] resolvePassword(Map<String, String> opts) {
        String p = getOpt(opts, "password", "p");

        if (p == null) {
            p = System.getenv(ENV_PASSWORD);
        }

        if (p != null && !p.isEmpty()) {
            return p.toCharArray();
        }

        if (System.console() != null) {
            char[] pw = System.console().readPassword("Пароль: ");
            if (pw == null || pw.length == 0) {
                throw new UsageException("Пароль не введён");
            }
            return pw;
        }

        throw new UsageException(
                "Пароль не задан. Используйте --password, переменную " + ENV_PASSWORD
                        + " или запустите из терминала интерактивно."
        );
    }

    // ---------- Справка ----------

    private static void printHelp() {
        System.out.print("""
                SoundBox %s — LSB-стеганография в WAV + AES-256

                Использование:
                  soundbox                                 запустить графический интерфейс
                  soundbox hide    -i <audio> -t <text>   спрятать текст в аудио
                  soundbox extract -i <audio>             извлечь текст из аудио
                  soundbox help                            показать эту справку
                  soundbox version                         показать версию

                Общие параметры:
                  -i, --input   <file>   входной аудиофайл (рекомендуется WAV PCM 16-bit)
                  -p, --password <pw>    пароль; если не задан — спросит интерактивно,
                                         либо возьмёт из переменной SOUNDBOX_PASSWORD
                  -o, --output  <file>   выходной файл
                                         (hide: по умолчанию <имя>_stego.wav рядом с исходником,
                                          extract: по умолчанию печать в stdout)

                Только для hide:
                  -t, --text      <text>  текст для сокрытия ("-" = читать из stdin)
                      --text-file <file>  прочитать текст из файла UTF-8

                Примеры:
                  soundbox hide -i audio.wav -t "секрет" -p qwerty
                  soundbox hide -i audio.wav --text-file secret.txt -p qwerty
                  cat secret.txt | soundbox hide -i audio.wav -t - -p qwerty
                  soundbox extract -i audio_stego.wav -p qwerty
                  soundbox extract -i audio_stego.wav -p qwerty -o secret.txt

                Коды возврата:
                  0 — успех
                  1 — ошибка выполнения (неверный пароль, недостаток места и т. п.)
                  2 — ошибка использования аргументов
                """.formatted(VERSION));
    }

    // ---------- Исключение ----------

    private static final class UsageException extends RuntimeException {
        UsageException(String message) {
            super(message);
        }
    }
}