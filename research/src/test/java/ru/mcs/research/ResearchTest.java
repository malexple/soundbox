package ru.mcs.research;

import org.junit.jupiter.api.Test;
import ru.mcs.research.bench.Attacks;
import ru.mcs.research.carriers.EchoCarrier;
import ru.mcs.research.carriers.PhaseCodingCarrier;
import ru.mcs.research.carriers.SpreadSpectrumCarrier;
import ru.mcs.research.carriers.TransformQimCarrier;
import ru.mcs.research.core.BlockCarrier;
import ru.mcs.research.core.StegoChannel;
import ru.mcs.research.dsp.Dct2;
import ru.mcs.research.dsp.HaarWavelet;
import ru.mcs.research.dsp.Signals;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ResearchTest {

    private static double[] noiseBlock(int n, long seed) {
        Random rnd = new Random(seed);
        double[] x = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = rnd.nextGaussian();
        }
        return Signals.normalizePeak(x, 0.5);
    }

    private static void assertCarrierRoundTrip(BlockCarrier carrier, double[] block) {
        double[] a = block.clone();
        carrier.writeBit(a, 0, true);
        assertTrue(carrier.readBit(a, 0), carrier.name() + ": bit=1");

        double[] b = block.clone();
        carrier.writeBit(b, 0, false);
        assertFalse(carrier.readBit(b, 0), carrier.name() + ": bit=0");
    }

    // ---------- DSP ----------

    @Test
    void dctRoundTrip() {
        int n = 64;
        Dct2 dct = new Dct2(n);
        double[] x = noiseBlock(n, 1);

        double[] back = dct.inverse(dct.forward(x));

        for (int i = 0; i < n; i++) {
            assertEquals(x[i], back[i], 1e-9);
        }
    }

    @Test
    void dctDcCoefficient() {
        int n = 32;
        Dct2 dct = new Dct2(n);

        double[] x = new double[n];
        Arrays.fill(x, 2.0);

        double[] spectrum = dct.forward(x);

        assertEquals(2.0 * Math.sqrt(n), spectrum[0], 1e-9);
        for (int k = 1; k < n; k++) {
            assertEquals(0.0, spectrum[k], 1e-9);
        }
    }

    @Test
    void haarRoundTrip() {
        int n = 64;
        double[] x = noiseBlock(n, 2);
        double[] back = HaarWavelet.inverse(HaarWavelet.forward(x));

        for (int i = 0; i < n; i++) {
            assertEquals(x[i], back[i], 1e-9);
        }
    }

    @Test
    void haarPreservesEnergy() {
        double[] x = noiseBlock(64, 3);
        double[] t = HaarWavelet.forward(x);

        double e1 = 0.0;
        double e2 = 0.0;
        for (int i = 0; i < x.length; i++) {
            e1 += x[i] * x[i];
            e2 += t[i] * t[i];
        }

        assertEquals(e1, e2, 1e-9);
    }

    // ---------- Носители ----------

    @Test
    void spreadSpectrumRoundTrip() {
        assertCarrierRoundTrip(
                new SpreadSpectrumCarrier(256, 0.06, 42),
                noiseBlock(256, 4)
        );
    }

    @Test
    void phaseCodingRoundTrip() {
        assertCarrierRoundTrip(
                new PhaseCodingCarrier(256, 16_000, 1000.0, 0.08),
                noiseBlock(256, 5)
        );
    }

    @Test
    void echoRoundTripOnAperiodicSignal() {
        assertCarrierRoundTrip(
                new EchoCarrier(256, 32, 64, 0.25),
                noiseBlock(256, 6)
        );
    }

    @Test
    void dctQimRoundTrip() {
        assertCarrierRoundTrip(
                new TransformQimCarrier(TransformQimCarrier.Domain.DCT, 256, 4, 0.05),
                noiseBlock(256, 7)
        );
    }

    @Test
    void haarQimRoundTrip() {
        assertCarrierRoundTrip(
                new TransformQimCarrier(TransformQimCarrier.Domain.HAAR, 256, 1, 0.05),
                noiseBlock(256, 8)
        );
    }

    // ---------- Канал ----------

    @Test
    void channelRoundTrip() {
        StegoChannel channel = new StegoChannel(
                new SpreadSpectrumCarrier(256, 0.06, 42), 3
        );

        byte[] payload = "HI!".getBytes(StandardCharsets.UTF_8);
        double[] signal = Signals.normalizePeak(noiseBlock(40_000, 9), 0.5);

        double[] stego = channel.embed(signal, payload);

        assertArrayEquals(payload, channel.extract(stego));
    }

    @Test
    void gainDoesNotBreakSpreadSpectrum() {
        StegoChannel channel = new StegoChannel(
                new SpreadSpectrumCarrier(256, 0.06, 42), 3
        );

        byte[] payload = "HI!".getBytes(StandardCharsets.UTF_8);
        double[] signal = Signals.normalizePeak(noiseBlock(40_000, 10), 0.5);

        double[] stego = channel.embed(signal, payload);
        double[] gained = Attacks.gain(stego, 0.3);

        assertArrayEquals(payload, channel.extract(gained));
    }

    @Test
    void repetitionCorrectsSingleRepetitionError() {
        // Искусственный носитель с блоком в 1 отсчёт:
        // позволяет детерминированно ломать ровно одно повторение.
        BlockCarrier raw = new BlockCarrier() {
            @Override
            public String name() {
                return "raw";
            }

            @Override
            public int blockSize() {
                return 1;
            }

            @Override
            public void writeBit(double[] signal, int offset, boolean bit) {
                signal[offset] = bit ? 1.0 : 0.0;
            }

            @Override
            public boolean readBit(double[] signal, int offset) {
                return signal[offset] > 0.5;
            }
        };

        StegoChannel channel = new StegoChannel(raw, 3);
        byte[] payload = {1, 2};

        double[] signal = channel.embed(new double[200], payload);

        boolean[] framed = StegoChannel.frame(payload);
        boolean[] read = channel.readBits(signal);

        assertTrue(
                read.length >= framed.length,
                "readBits должен вернуть не меньше бит, чем занимает кадр"
        );

        // Сравниваем только полезный префикс, а не весь массив целиком.
        assertArrayEquals(framed, Arrays.copyOf(read, framed.length));

        // Ломает одно из трёх повторений логического бита №20.
        int block = 20 * 3 + 1;
        signal[block] = signal[block] > 0.5 ? 0.0 : 1.0;

        // После поломки одного повторения кадр всё ещё должен читаться правильно.
        boolean[] readAfterCorruption = channel.readBits(signal);
        assertArrayEquals(
                framed,
                Arrays.copyOf(readAfterCorruption, framed.length),
                "повторный код должен исправить одиночную ошибку"
        );

        assertArrayEquals(payload, channel.extract(signal));
    }

    @Test
    void insufficientCapacityThrows() {
        StegoChannel channel = new StegoChannel(
                new SpreadSpectrumCarrier(256, 0.06, 42), 3
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> channel.embed(new double[1000], new byte[]{1, 2, 3})
        );
    }

    @Test
    void evenRepetitionIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new StegoChannel(new SpreadSpectrumCarrier(256, 0.06, 1), 2)
        );
    }
}