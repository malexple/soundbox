package ru.mcs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFormat;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CliTest {

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
    void helpAndVersionReturnSuccess() {
        assertEquals(0, Cli.run(new String[]{"help"}));
        assertEquals(0, Cli.run(new String[]{"version"}));
    }

    @Test
    void unknownCommandReturnsUsageError() {
        assertEquals(2, Cli.run(new String[]{"unknown-command"}));
    }

    @Test
    void hideAndExtractRoundTrip(@TempDir Path tempDir) throws Exception {
        File input = tempDir.resolve("input.wav").toFile();
        AudioEngine.writeWav(input, mono16le(), new byte[4096]);

        File output = tempDir.resolve("output.wav").toFile();

        int hideCode = Cli.run(new String[]{
                "hide",
                "-i", input.getAbsolutePath(),
                "-t", "cli test",
                "-p", "password",
                "-o", output.getAbsolutePath()
        });

        assertEquals(0, hideCode);
        assertTrue(output.exists());

        Path extracted = tempDir.resolve("extracted.txt");

        int extractCode = Cli.run(new String[]{
                "extract",
                "-i", output.getAbsolutePath(),
                "-p", "password",
                "-o", extracted.toString()
        });

        assertEquals(0, extractCode);
        assertEquals("cli test", Files.readString(extracted));
    }
}