package com.nhl.dflib.benchmark.speed;

import com.nhl.dflib.DataFrame;
import com.nhl.dflib.benchmark.DataGenerator;
import com.nhl.dflib.benchmark.ValueMaker;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(2)
@State(Scope.Thread)
public class DataFrameHashJoin {

    @Param("1000000")
    public int rows;

    @Param("500000")
    public int joinGroups;

    private DataFrame df1;
    private DataFrame df2;

    @Setup
    public void setUp() {
        df1 = DataGenerator.columnarDF(rows,
                // this column is used in join. Must be predictable and overlap with keys in df2
                ValueMaker.intSeq(joinGroups / 2, (int) (joinGroups * 1.5)),
                ValueMaker.stringSeq(),
                ValueMaker.randomIntSeq(rows / 2),
                ValueMaker.constStringSeq("abcd"));

        df2 = DataGenerator.columnarDF(rows,
                ValueMaker.intSeq(),
                ValueMaker.stringSeq(),
                // this column is used in join. Must be predictable and overlap with keys in df1
                ValueMaker.intSeq(1, joinGroups),
                ValueMaker.constStringSeq("abcd"));
    }

    @Benchmark
    public Object leftJoin() {
        return df1
                .leftJoin()
                .on("c0", "c2")
                .with(df2)
                .materialize()
                .iterator();
    }

    @Benchmark
    public Object leftJoin_ByPosition() {
        return df1
                .leftJoin()
                .on(0, 2)
                .with(df2)
                .materialize()
                .iterator();
    }

    @Benchmark
    public Object rightJoin() {
        return df1
                .rightJoin()
                .on("c0", "c2")
                .with(df2)
                .materialize()
                .iterator();
    }

    @Benchmark
    public Object innerJoin() {
        return df1
                .innerJoin()
                .on("c0", "c2")
                .with(df2)
                .materialize()
                .iterator();
    }

    @Benchmark
    public Object fullJoin() {
        return df1
                .fullJoin()
                .on("c0", "c2")
                .with(df2)
                .materialize()
                .iterator();
    }
}
