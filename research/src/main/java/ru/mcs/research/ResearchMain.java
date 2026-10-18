package ru.mcs.research;

import ru.mcs.research.bench.Benchmark;

public final class ResearchMain {

    private ResearchMain() {
    }

    public static void main(String[] args) {
        System.out.println("SoundBox / Variant C - research bench");
        System.out.println("Physical layer only: no crypto, no sync header");
        System.out.println();

        Benchmark.print(Benchmark.run());
    }
}