package ru.mcs.research.core;

/**
 * Исследовательский канал поверх BlockCarrier.
 *
 * Не содержит криптографии: это стенд для измерения физического уровня.
 * AES/GCM остаётся в основном проекте.
 */
public final class StegoChannel {

    private static final int HEADER_BITS = 16;

    private final BlockCarrier carrier;
    private final int repetition;

    public StegoChannel(BlockCarrier carrier, int repetition) {
        if (repetition < 1 || repetition % 2 == 0) {
            throw new IllegalArgumentException(
                    "repetition должен быть положительным и нечётным (1, 3, 5, ...)"
            );
        }
        this.carrier = carrier;
        this.repetition = repetition;
    }

    public BlockCarrier carrier() {
        return carrier;
    }

    public int repetition() {
        return repetition;
    }

    public int capacityBytes(int signalLength) {
        int bits = signalLength / carrier.blockSize() / repetition;
        return Math.max(0, (bits - HEADER_BITS) / 8);
    }

    /** Кадрирование: 16 бит длины + байты payload, MSB-first. */
    public static boolean[] frame(byte[] payload) {
        if (payload == null || payload.length == 0) {
            throw new IllegalArgumentException("payload пустой");
        }
        if (payload.length > 0xFFFF) {
            throw new IllegalArgumentException("payload слишком большой для 16-битной длины");
        }

        boolean[] bits = new boolean[HEADER_BITS + payload.length * 8];

        for (int i = 0; i < HEADER_BITS; i++) {
            bits[i] = ((payload.length >>> (HEADER_BITS - 1 - i)) & 1) != 0;
        }

        for (int b = 0; b < payload.length; b++) {
            for (int i = 0; i < 8; i++) {
                bits[HEADER_BITS + b * 8 + i] = ((payload[b] >>> (7 - i)) & 1) != 0;
            }
        }

        return bits;
    }

    public double[] embed(double[] signal, byte[] payload) {
        boolean[] bits = frame(payload);

        int blockSize = carrier.blockSize();
        int available = signal.length / blockSize;
        int needed = bits.length * repetition;

        if (needed > available) {
            throw new IllegalArgumentException(String.format(
                    "Недостаточно места: нужно %d блоков, доступно %d",
                    needed, available
            ));
        }

        double[] out = signal.clone();

        for (int i = 0; i < bits.length; i++) {
            for (int r = 0; r < repetition; r++) {
                int offset = (i * repetition + r) * blockSize;
                carrier.writeBit(out, offset, bits[i]);
            }
        }

        // Защитный clamp. В стенде сигналы нормализованы так,
        // чтобы он не срабатывал и не портил измерения.
        for (int i = 0; i < out.length; i++) {
            out[i] = Math.max(-1.0, Math.min(1.0, out[i]));
        }

        return out;
    }

    /** Сырые биты после голосования. Нужны для измерения BER. */
    public boolean[] readBits(double[] signal) {
        int blockSize = carrier.blockSize();
        int available = signal.length / blockSize;
        int count = available / repetition;

        boolean[] bits = new boolean[count];

        for (int i = 0; i < count; i++) {
            int votes = 0;
            for (int r = 0; r < repetition; r++) {
                int offset = (i * repetition + r) * blockSize;
                if (carrier.readBit(signal, offset)) {
                    votes++;
                }
            }
            bits[i] = votes * 2 > repetition;
        }

        return bits;
    }

    public byte[] extract(double[] signal) {
        boolean[] bits = readBits(signal);

        if (bits.length < HEADER_BITS) {
            throw new IllegalStateException("Сигнал слишком короткий");
        }

        int length = 0;
        for (int i = 0; i < HEADER_BITS; i++) {
            length = (length << 1) | (bits[i] ? 1 : 0);
        }

        if (length <= 0 || HEADER_BITS + length * 8L > bits.length) {
            throw new IllegalStateException("Скрытые данные не найдены или повреждены");
        }

        byte[] out = new byte[length];

        for (int b = 0; b < length; b++) {
            int value = 0;
            for (int i = 0; i < 8; i++) {
                value = (value << 1) | (bits[HEADER_BITS + b * 8 + i] ? 1 : 0);
            }
            out[b] = (byte) value;
        }

        return out;
    }
}