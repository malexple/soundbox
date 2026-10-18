package ru.mcs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFormat;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class AudioEngineTest {

    private static final char[] PASSWORD = "password123".toCharArray();

    private static byte[] silentPcm(int length) {
        return new byte[length];
    }

    private static AudioFormat mono16le() {
        return new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                44100.0f,
                16,
                1,
                2,
                44100.0f,
                false
        );
    }

    @Test
    void capacityBytesUsesOneBitPer16BitSample() {
        assertEquals(0, AudioEngine.capacityBytes(new byte[0]));
        assertEquals(0, AudioEngine.capacityBytes(new byte[15]));
        assertEquals(1, AudioEngine.capacityBytes(new byte[16]));
        assertEquals(1, AudioEngine.capacityBytes(new byte[31]));
        assertEquals(2, AudioEngine.capacityBytes(new byte[32]));
        assertEquals(256, AudioEngine.capacityBytes(new byte[4096]));
    }

    @Test
    void payloadSizeIncludesCryptoOverhead() {
        int expectedEmptyText = 4 + 16 + 12 + 0 + 16;
        int expectedThreeBytes = 4 + 16 + 12 + 3 + 16;

        assertEquals(expectedEmptyText, AudioEngine.payloadSize(0));
        assertEquals(expectedThreeBytes, AudioEngine.payloadSize(3));

        assertEquals(
                AudioEngine.payloadSize("abc".getBytes(StandardCharsets.UTF_8).length),
                AudioEngine.payloadSize("abc")
        );
    }

    @Test
    void hideAndExtractRoundTrip() {
        byte[] original = silentPcm(4096);
        String text = "Hello, SoundBox!";

        byte[] stego = AudioEngine.hideText(original, text, PASSWORD);

        assertFalse(Arrays.equals(original, stego));
        assertEquals(text, AudioEngine.extractText(stego, PASSWORD));
    }

    @Test
    void wrongPasswordFails() {
        byte[] stego = AudioEngine.hideText(
                silentPcm(4096),
                "secret",
                PASSWORD
        );

        assertThrows(
                IllegalStateException.class,
                () -> AudioEngine.extractText(stego, "wrong-password".toCharArray())
        );
    }

    @Test
    void modifiedStegoDataFailsAuthentication() {
        byte[] stego = AudioEngine.hideText(
                silentPcm(4096),
                "integrity",
                PASSWORD
        );

        // Меняем один спрятанный бит.
        stego[100] ^= 0x01;

        assertThrows(
                IllegalStateException.class,
                () -> AudioEngine.extractText(stego, PASSWORD)
        );
    }

    @Test
    void extractFromCleanPcmFindsNothing() {
        assertThrows(
                IllegalStateException.class,
                () -> AudioEngine.extractText(silentPcm(4096), PASSWORD)
        );
    }

    @Test
    void insufficientCapacityThrows() {
        // Ёмкость здесь 2 байта, а зашифрованное сообщение занимает больше.
        byte[] tooSmall = silentPcm(32);

        assertThrows(
                IllegalArgumentException.class,
                () -> AudioEngine.hideText(tooSmall, "abc", PASSWORD)
        );
    }

    @Test
    void emptyTextOrPasswordThrows() {
        byte[] pcm = silentPcm(4096);

        assertThrows(
                IllegalArgumentException.class,
                () -> AudioEngine.hideText(pcm, "", PASSWORD)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> AudioEngine.hideText(pcm, null, PASSWORD)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> AudioEngine.hideText(pcm, "abc", new char[0])
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> AudioEngine.hideText(pcm, "abc", null)
        );
    }

    @Test
    void oddPcmLengthRejected() {
        byte[] odd = new byte[17];

        assertThrows(
                IllegalArgumentException.class,
                () -> AudioEngine.hideText(odd, "abc", PASSWORD)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> AudioEngine.extractText(odd, PASSWORD)
        );
    }

    @Test
    void buildOutputFileAvoidsCollisions(@TempDir Path tempDir) throws Exception {
        File source = tempDir.resolve("audio.wav").toFile();
        Files.createFile(source.toPath());

        File first = AudioEngine.buildOutputFile(source);
        assertEquals("audio_stego.wav", first.getName());
        assertFalse(first.exists());

        Files.createFile(first.toPath());

        File second = AudioEngine.buildOutputFile(source);
        assertEquals("audio_stego_1.wav", second.getName());
    }

    @Test
    void wavRoundTripPreservesHiddenText(@TempDir Path tempDir) throws Exception {
        byte[] original = silentPcm(4096);
        String text = "wav round trip";

        byte[] stego = AudioEngine.hideText(original, text, PASSWORD);

        File wav = tempDir.resolve("stego.wav").toFile();
        AudioEngine.writeWav(wav, mono16le(), stego);

        AudioEngine.PcmAudio loaded = AudioEngine.loadPcm(wav);

        assertEquals(text, AudioEngine.extractText(loaded.data(), PASSWORD));
    }
}