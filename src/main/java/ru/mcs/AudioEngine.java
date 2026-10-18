package ru.mcs;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Ядро аудио-стеганографии: LSB-встраивание в 16-битный PCM + AES-256/GCM.
 *
 * Формат скрытых данных:
 *   [4 байта: длина payload] [payload]
 *
 * payload:
 *   [16 байт salt][12 байт IV][шифротекст + 16 байт auth tag]
 *
 * Встраивание:
 *   1 бит прячется в младший бит каждого 16-битного сэмпла.
 *   Используем little-endian PCM, поэтому младший байт сэмпла идёт первым.
 */
public final class AudioEngine {

    public record PcmAudio(AudioFormat format, byte[] data) {
    }

    private static final int SALT_LEN = 16;
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final int TAG_BYTES = TAG_BITS / 8;
    private static final int PBKDF2_ITERATIONS = 120_000;
    private static final int KEY_BITS = 256;

    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final String KDF = "PBKDF2WithHmacSHA256";

    private AudioEngine() {
    }

    // ---------- Публичный API ----------

    /**
     * Читит аудиофайл и конвертирует его в 16-битный PCM little-endian.
     *
     * Сейчас надёжно поддерживаются WAV-файлы, которые Java Sound может
     * прочитать/сконвертировать. Для гарантированной работы лучше использовать
     * WAV PCM 16-bit.
     */
    public static PcmAudio loadPcm(File file) throws Exception {
        try (AudioInputStream in = AudioSystem.getAudioInputStream(file)) {
            AudioFormat base = in.getFormat();

            int channels = base.getChannels();
            float sampleRate = base.getSampleRate();

            if (channels <= 0 || sampleRate <= 0.0f) {
                throw new IllegalArgumentException(
                        "Некорректный аудиофайл: неизвестная частота или число каналов"
                );
            }

            AudioFormat target = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    sampleRate,
                    16,
                    channels,
                    channels * 2, // frame size: 2 байта на канал
                    sampleRate,
                    false           // little endian
            );

            if (!AudioSystem.isConversionSupported(target, base)) {
                throw new IllegalArgumentException(
                        "Не удаётся сконвертировать файл в 16-битный PCM. " +
                                "Используйте WAV PCM 16-bit, например: " +
                                "ffmpeg -i input.mp3 -c:a pcm_s16le output.wav"
                );
            }

            try (AudioInputStream pcm = AudioSystem.getAudioInputStream(target, in)) {
                byte[] data = pcm.readAllBytes();

                if (data.length == 0 || data.length % 2 != 0) {
                    throw new IllegalStateException(
                            "Ожидается поток 16-битных PCM-данных"
                    );
                }

                return new PcmAudio(target, data);
            }
        }
    }

    /**
     * Прячет зашифрованный текст в 16-битный PCM.
     *
     * @param pcmData  PCM-данные, полученные из {@link #loadPcm(File)}
     * @param text     текст для сокрытия
     * @param password пароль
     * @return новые PCM-данные со спрятанным текстом
     */
    public static byte[] hideText(byte[] pcmData, String text, char[] password) {
        validatePcm(pcmData);

        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("Текст пустой");
        }

        if (password == null || password.length == 0) {
            throw new IllegalArgumentException("Пароль пустой");
        }

        byte[] payload = encrypt(text, password);

        byte[] data = new byte[4 + payload.length];
        data[0] = (byte) (payload.length >>> 24);
        data[1] = (byte) (payload.length >>> 16);
        data[2] = (byte) (payload.length >>> 8);
        data[3] = (byte) payload.length;
        System.arraycopy(payload, 0, data, 4, payload.length);

        long capacity = capacityBytes(pcmData);

        if (data.length > capacity) {
            throw new IllegalArgumentException(String.format(
                    "Недостаточно места: нужно %s, доступно %s. " +
                            "Возьмите более длинный аудиофайл или сократите текст.",
                    humanBytes(data.length), humanBytes(capacity)
            ));
        }

        byte[] out = pcmData.clone();
        embedBits(out, data);
        return out;
    }

    /**
     * Извлекает и расшифровывает текст из 16-битного PCM.
     */
    public static String extractText(byte[] pcmData, char[] password) {
        validatePcm(pcmData);

        if (password == null || password.length == 0) {
            throw new IllegalArgumentException("Пароль пустой");
        }

        byte[] header = readBytes(pcmData, 0, 4);

        int payloadLen = ((header[0] & 0xFF) << 24)
                | ((header[1] & 0xFF) << 16)
                | ((header[2] & 0xFF) << 8)
                | (header[3] & 0xFF);

        long capacity = capacityBytes(pcmData);

        if (payloadLen <= 0 || payloadLen + 4L > capacity) {
            throw new IllegalStateException("Скрытые данные не найдены");
        }

        byte[] payload = readBytes(pcmData, 4, payloadLen);

        try {
            return decrypt(payload, password);
        } catch (Exception e) {
            throw new IllegalStateException("Неверный пароль или повреждённые данные", e);
        }
    }

    /**
     * Сохраняет PCM-данные как WAV.
     */
    public static void writeWav(File output, AudioFormat format, byte[] pcmData) throws Exception {
        long frames = format.getFrameSize() > 0
                ? pcmData.length / format.getFrameSize()
                : 0;

        try (AudioInputStream out = new AudioInputStream(
                new ByteArrayInputStream(pcmData),
                format,
                frames
        )) {
            AudioSystem.write(out, AudioFileFormat.Type.WAVE, output);
        }
    }

    /**
     * Ёмкость в байтах для 16-битного PCM.
     *
     * 16-битный сэмпл = 2 байта.
     * Мы прячем 1 бит в один сэмпл.
     *
     * Поэтому:
     *   pcmData.length / 2 = количество сэмплов
     *   количество сэмплов / 8 = количество байт, которые можно спрятать
     *
     * Итого:
     *   pcmData.length / 16
     */
    public static long capacityBytes(byte[] pcmData) {
        if (pcmData == null) {
            return 0;
        }

        return pcmData.length / 16L;
    }

    /**
     * Сколько байт займёт зашифрованный текст заданной длины.
     */
    public static long payloadSize(int textBytesLength) {
        return 4L + SALT_LEN + IV_LEN + textBytesLength + TAG_BYTES;
    }

    /**
     * Сколько байт займёт строка после шифрования.
     */
    public static long payloadSize(String text) {
        return payloadSize(text.getBytes(StandardCharsets.UTF_8).length);
    }

    /**
     * Человекочитаемый размер.
     */
    public static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " Б";
        }

        if (bytes < 1024 * 1024) {
            return String.format("%.1f КБ", bytes / 1024.0);
        }

        return String.format("%.2f МБ", bytes / (1024.0 * 1024));
    }

    /**
     * Формирует путь к выходному файлу рядом с исходником:
     * <имя>_stego.wav
     */
    public static File buildOutputFile(File source) {
        File dir = source.getAbsoluteFile().getParentFile();
        String name = source.getName();

        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;

        File candidate = new File(dir, base + "_stego.wav");

        int counter = 1;
        while (candidate.exists()) {
            candidate = new File(dir, base + "_stego_" + counter + ".wav");
            counter++;
        }

        return candidate;
    }

    // ---------- Встраивание / чтение бит ----------

    private static void validatePcm(byte[] pcmData) {
        if (pcmData == null || pcmData.length == 0 || pcmData.length % 2 != 0) {
            throw new IllegalArgumentException("Некорректные PCM-данные");
        }
    }

    private static void embedBits(byte[] audio, byte[] data) {
        long totalBits = data.length * 8L;
        long totalSamples = audio.length / 2L;

        if (totalBits > totalSamples) {
            throw new IllegalArgumentException("Недостаточно места для встраивания");
        }

        int totalBitsInt = Math.toIntExact(totalBits);

        for (int bitIndex = 0; bitIndex < totalBitsInt; bitIndex++) {
            /*
             * Используем little-endian PCM.
             * Один 16-битный сэмпл занимает 2 байта.
             * Младший байт идёт первым, поэтому его индекс = bitIndex * 2.
             */
            int lowByteIndex = bitIndex * 2;

            int bit = nextBit(data, bitIndex);
            audio[lowByteIndex] = (byte) ((audio[lowByteIndex] & 0xFE) | bit);
        }
    }

    private static int nextBit(byte[] data, int bitIndex) {
        int byteIndex = bitIndex >> 3;
        int bitInByte = 7 - (bitIndex & 7);
        return (data[byteIndex] >> bitInByte) & 1;
    }

    private static byte[] readBytes(byte[] audio, int startByte, int count) {
        if (count < 0) {
            throw new IllegalArgumentException("Отрицательное количество байтов");
        }

        long startBit = (long) startByte * 8L;
        long needBits = (long) count * 8L;
        long totalBits = audio.length / 2L;

        if (startBit + needBits > totalBits) {
            throw new IllegalStateException("Недостаточно данных в аудио");
        }

        byte[] result = new byte[count];

        int current = 0;
        int bits = 0;
        int written = 0;

        for (long bit = startBit; bit < startBit + needBits; bit++) {
            int lowByteIndex = (int) (bit * 2L);
            int b = audio[lowByteIndex] & 1;

            current = (current << 1) | b;
            bits++;

            if (bits == 8) {
                result[written++] = (byte) current;
                current = 0;
                bits = 0;
            }
        }

        return result;
    }

    // ---------- Криптография ----------

    private static byte[] encrypt(String text, char[] password) {
        try {
            SecureRandom rnd = new SecureRandom();

            byte[] salt = new byte[SALT_LEN];
            byte[] iv = new byte[IV_LEN];

            rnd.nextBytes(salt);
            rnd.nextBytes(iv);

            SecretKey key = deriveKey(password, salt);

            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));

            byte[] cipherText = cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));

            byte[] payload = new byte[SALT_LEN + IV_LEN + cipherText.length];
            System.arraycopy(salt, 0, payload, 0, SALT_LEN);
            System.arraycopy(iv, 0, payload, SALT_LEN, IV_LEN);
            System.arraycopy(cipherText, 0, payload, SALT_LEN + IV_LEN, cipherText.length);

            return payload;
        } catch (Exception e) {
            throw new IllegalStateException("Ошибка шифрования: " + e.getMessage(), e);
        }
    }

    private static String decrypt(byte[] payload, char[] password) throws Exception {
        if (payload.length < SALT_LEN + IV_LEN + TAG_BYTES) {
            throw new IllegalStateException("Повреждённые данные");
        }

        byte[] salt = Arrays.copyOfRange(payload, 0, SALT_LEN);
        byte[] iv = Arrays.copyOfRange(payload, SALT_LEN, SALT_LEN + IV_LEN);
        byte[] cipherText = Arrays.copyOfRange(payload, SALT_LEN + IV_LEN, payload.length);

        SecretKey key = deriveKey(password, salt);

        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));

        byte[] plain = cipher.doFinal(cipherText);

        return new String(plain, StandardCharsets.UTF_8);
    }

    private static SecretKey deriveKey(char[] password, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_BITS);

        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(KDF);
            byte[] keyBytes = factory.generateSecret(spec).getEncoded();
            return new SecretKeySpec(keyBytes, "AES");
        } finally {
            spec.clearPassword();
        }
    }
}