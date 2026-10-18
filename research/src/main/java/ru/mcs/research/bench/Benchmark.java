package ru.mcs.research.bench;

import ru.mcs.research.carriers.EchoCarrier;
import ru.mcs.research.carriers.PhaseCodingCarrier;
import ru.mcs.research.carriers.SpreadSpectrumCarrier;
import ru.mcs.research.carriers.TransformQimCarrier;
import ru.mcs.research.core.BlockCarrier;
import ru.mcs.research.core.StegoChannel;
import ru.mcs.research.core.SyncStegoChannel;
import ru.mcs.research.dsp.Signals;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.Locale;

public final class Benchmark {

    private static final int FS = 16_000;
    private static final int BLOCK = 256;
    private static final int REPETITION = 3;
    private static final int SECONDS = 6;

    private static final byte[] MESSAGE = "ABCDEF12".getBytes(StandardCharsets.UTF_8);

    public record Row(String signal, String method, String attack,
                      double ber, boolean exact, double snrDb) {
    }

    private record Signal(String name, Supplier<double[]> factory) {
    }

    private record Attack(String name, UnaryOperator<double[]> fn) {
    }

    private Benchmark() {
    }

    private static List<Signal> signals() {
        int n = FS * SECONDS;

        return List.of(
                new Signal("tone", () -> tone(n, 440.0)),
                new Signal("noise", () -> whiteNoise(n, 7)),
                new Signal("speech-like", () -> speechLike(n))
        );
    }

    private static List<Supplier<BlockCarrier>> carriers() {
        return List.of(
                () -> new SpreadSpectrumCarrier(BLOCK, 0.06, 42),
                () -> new PhaseCodingCarrier(BLOCK, FS, 1000.0, 0.08),
                () -> new EchoCarrier(BLOCK, 32, 64, 0.25),
                () -> new TransformQimCarrier(
                        TransformQimCarrier.Domain.DCT, BLOCK, 4, 0.05),
                () -> new TransformQimCarrier(
                        TransformQimCarrier.Domain.HAAR, BLOCK, 1, 0.05)
        );
    }

    private static List<Attack> attacks() {
        return List.of(
                new Attack("none", x -> x.clone()),
                new Attack("gain0.5", x -> Attacks.gain(x, 0.5)),
                new Attack("gain2", x -> Attacks.gain(x, 2.0)),
                new Attack("noise30", x -> Attacks.noise(x, 30.0, 11)),
                new Attack("noise20", x -> Attacks.noise(x, 20.0, 11)),
                new Attack("lowpass8", x -> Attacks.lowpass(x, 8)),
                new Attack("crop137", x -> Attacks.crop(x, 137)),
                new Attack("pad512", x -> Attacks.padStart(x, 512)),
                new Attack("crop512", x -> Attacks.crop(x, 512)),
                new Attack("lp16", x -> Attacks.lowpass(x, 16)),
                new Attack("pad137", x -> Attacks.padStart(x, 137))
        );
    }

    public static List<Row> run() {
        List<Row> rows = new ArrayList<>();

        for (Signal signal : signals()) {
            double[] base = Signals.normalizePeak(signal.factory().get(), 0.5);

            for (Supplier<BlockCarrier> factory : carriers()) {
                BlockCarrier carrier = factory.get();

                SyncStegoChannel channel = new SyncStegoChannel(carrier, REPETITION);

                double[] stego = channel.embed(base, MESSAGE);
                double snr = Metrics.snrDb(base, stego);
                boolean[] expected = StegoChannel.frame(MESSAGE);

                for (Attack attack : attacks()) {
                    double[] attacked = attack.fn().apply(stego);

                    boolean[] received;
                    try {
                        received = channel.readFramedBits(attacked);
                    } catch (RuntimeException e) {
                        received = new boolean[0];
                    }

                    double ber = Metrics.ber(expected, received);

                    boolean exact;
                    try {
                        exact = Arrays.equals(MESSAGE, channel.extract(attacked));
                    } catch (RuntimeException e) {
                        exact = false;
                    }

                    rows.add(new Row(
                            signal.name(), carrier.name(), attack.name(),
                            ber, exact, snr
                    ));
                }
            }
        }

        return rows;
    }

    public static void print(List<Row> rows) {
        System.out.printf(Locale.ROOT,
                "fs=%d Hz, block=%d, repetition=%d -> %.1f bit/s, %.2f B/s%n",
                FS, BLOCK, REPETITION,
                (double) FS / BLOCK / REPETITION,
                (double) FS / BLOCK / REPETITION / 8.0
        );

        System.out.println("message: \"" + new String(MESSAGE, StandardCharsets.UTF_8)
                + "\", payload=" + MESSAGE.length + " bytes");
        System.out.println();
        System.out.println("+ = payload recovered exactly, - = no");
        System.out.println();

        Map<String, List<Row>> bySignal = new LinkedHashMap<>();
        for (Row r : rows) {
            bySignal.computeIfAbsent(r.signal(), k -> new ArrayList<>()).add(r);
        }

        List<String> attackNames = attacks().stream().map(Attack::name).toList();

        for (Map.Entry<String, List<Row>> entry : bySignal.entrySet()) {
            System.out.println("=== Signal: " + entry.getKey() + " ===");

            StringBuilder header = new StringBuilder(
                    String.format(Locale.ROOT, "%-18s", "method")
            );

            for (String name : attackNames) {
                header.append(" | ").append(String.format(Locale.ROOT, "%-9s", name));
            }

            System.out.println(header);

            Map<String, List<Row>> byMethod = new LinkedHashMap<>();
            for (Row r : entry.getValue()) {
                byMethod.computeIfAbsent(r.method(), k -> new ArrayList<>()).add(r);
            }

            for (Map.Entry<String, List<Row>> m : byMethod.entrySet()) {
                StringBuilder line = new StringBuilder(
                        String.format(Locale.ROOT, "%-18s", m.getKey())
                );

                for (Row r : m.getValue()) {
                    String cell = String.format(
                            Locale.ROOT,
                            "%.3f%s",
                            r.ber(),
                            r.exact() ? "+" : "-"
                    );

                    line.append(" | ").append(String.format(Locale.ROOT, "%-9s", cell));
                }

                System.out.println(line);
            }

            System.out.printf(Locale.ROOT, "Embedding SNR: %.1f dB%n%n",
                    entry.getValue().get(0).snrDb());
        }

        System.out.println("=== Summary: exact recovered / total ===");

        Map<String, int[]> summary = new LinkedHashMap<>();
        for (Row r : rows) {
            int[] counter = summary.computeIfAbsent(r.method(), k -> new int[2]);
            counter[1]++;
            if (r.exact()) {
                counter[0]++;
            }
        }

        for (Map.Entry<String, int[]> e : summary.entrySet()) {
            System.out.printf(Locale.ROOT, "%-18s %d / %d%n",
                    e.getKey(), e.getValue()[0], e.getValue()[1]);
        }
    }

    // ---------- Синтетические сигналы ----------

    private static double[] tone(int n, double frequency) {
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = Math.sin(2.0 * Math.PI * frequency * i / FS);
        }
        return out;
    }

    private static double[] whiteNoise(int n, long seed) {
        Random rnd = new Random(seed);
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = rnd.nextGaussian();
        }
        return out;
    }

    private static double[] speechLike(int n) {
        double[] out = new double[n];

        for (int i = 0; i < n; i++) {
            double t = (double) i / FS;
            double envelope = 0.5 + 0.5 * Math.sin(2.0 * Math.PI * 3.1 * t);

            double v = 0.0;
            for (int h = 1; h <= 6; h++) {
                v += Math.sin(2.0 * Math.PI * 180.0 * h * t) / h;
            }

            out[i] = envelope * v;
        }

        return out;
    }
}