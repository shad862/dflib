package com.nhl.dflib;

import com.nhl.dflib.series.ArraySeries;
import com.nhl.dflib.series.DoubleArraySeries;
import com.nhl.dflib.series.IntArraySeries;
import com.nhl.dflib.series.LongArraySeries;
import com.nhl.dflib.series.builder.DoubleAccumulator;
import com.nhl.dflib.series.builder.IntAccumulator;
import com.nhl.dflib.series.builder.LongAccumulator;
import com.nhl.dflib.series.builder.ObjectAccumulator;
import com.nhl.dflib.series.builder.SeriesBuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.PrimitiveIterator;
import java.util.function.Function;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

/**
 * Assembles a DataFrame from various in-memory data structures. Usually created via {@link DataFrame#newFrame(Index)}
 * or {@link DataFrame#newFrame(String...)}.
 *
 * @since 0.6
 */
public class DataFrameBuilder {

    private Index columnsIndex;

    protected DataFrameBuilder(Index columnsIndex) {
        this.columnsIndex = Objects.requireNonNull(columnsIndex);
    }

    public static DataFrameBuilder builder(String... columnLabels) {
        return builder(Index.forLabels(Objects.requireNonNull(columnLabels)));
    }

    public static DataFrameBuilder builder(Index columnsIndex) {
        return new DataFrameBuilder(columnsIndex);
    }

    public DataFrame empty() {
        return new ColumnDataFrame(columnsIndex);
    }

    public DataFrame columns(Series<?>... columns) {
        Objects.requireNonNull(columns);
        return new ColumnDataFrame(columnsIndex, columns);
    }

    public DataFrame rows(Object[]... rows) {
        int w = columnsIndex.size();
        int h = rows.length;

        // convert array of rows into an array of columns
        Object[][] columnarData = new Object[w][h];
        for (int i = 0; i < h; i++) {

            if (rows[i].length < w) {
                throw new IllegalArgumentException("Row must be at least " + w + " elements long: " + rows[i].length);
            }

            for (int j = 0; j < w; j++) {
                columnarData[j][i] = rows[i][j];
            }
        }

        return fromColumnarData(columnarData);
    }

    /**
     * @since 0.7
     * @return a builder that allows to append rows to the DataFrame one by one instead of copying from a collection.
     */
    public DataFrameByRowBuilder byRow() {
        return new DataFrameByRowBuilder(columnsIndex);
    }

    public DataFrameByRowBuilder addRow(Object... row) {
        return new DataFrameByRowBuilder(columnsIndex).addRow(row);
    }

    public DataFrame foldByRow(Object... data) {

        int width = columnsIndex.size();
        if (width == 0) {
            throw new IllegalArgumentException("Empty columns");
        }

        int lastRowWidth = data.length % width;

        int minHeight = data.length / width;
        int fullHeight = lastRowWidth > 0 ? minHeight + 1 : minHeight;

        Object[][] columnarData = new Object[width][fullHeight];

        for (int i = 0; i < minHeight; i++) {
            for (int j = 0; j < width; j++) {
                columnarData[j][i] = data[i * width + j];
            }
        }

        if (lastRowWidth > 0) {
            int lastRowIndex = minHeight;
            for (int j = 0; j < lastRowWidth; j++) {
                columnarData[j][lastRowIndex] = data[lastRowIndex * width + j];
            }
        }

        return fromColumnarData(columnarData);
    }

    public DataFrame foldByColumn(Object... data) {

        FoldByColumnGeometry g = byColumnGeometry(data.length);

        Object[][] columnarData = new Object[g.width][g.height];

        for (int i = 0; i < g.fullColumns; i++) {
            System.arraycopy(data, i * g.height, columnarData[i], 0, g.height);
        }

        if (g.isLastColumnPartial()) {
            System.arraycopy(data, g.cellsInFullColumns(), columnarData[g.fullColumns], 0, g.partialColumnHeight);
        }

        return fromColumnarData(columnarData);
    }

    public <T> DataFrame foldStreamByRow(Stream<T> stream) {
        return foldIterableByRow(() -> stream.iterator());
    }

    public <T> DataFrame foldStreamByColumn(Stream<T> stream) {
        // since we can't guess the height from the Stream, convert it to array and fold the array by column
        return foldByColumn(stream.toArray());
    }

    public <T> DataFrame foldIterableByRow(Iterable<T> iterable) {

        int width = columnsIndex.size();
        if (width == 0) {
            throw new IllegalArgumentException("Empty columns");
        }

        int heightEstimate = (iterable instanceof Collection) ? ((Collection) iterable).size() : 10;

        SeriesBuilder<Object, Object>[] columnBuilders = new ObjectAccumulator[width];
        for (int i = 0; i < width; i++) {
            columnBuilders[i] = new ObjectAccumulator<>(heightEstimate);
        }

        int p = 0;
        for (Object o : iterable) {
            columnBuilders[p % width].add(o);
            p++;
        }

        // fill the last row to the end
        int pl = p % width;
        if (pl > 0) {
            for (; pl < width; pl++) {
                columnBuilders[pl].add(null);
            }
        }

        Series<?>[] series = new Series[columnBuilders.length];
        for (int i = 0; i < columnBuilders.length; i++) {
            series[i] = columnBuilders[i].toSeries();
        }

        return new ColumnDataFrame(columnsIndex, series);
    }

    public <T> DataFrame foldIterableByColumn(Iterable<T> iterable) {

        // since we can't know the exact size of the Iterable in a general case, convert it to array and fold that by
        // column
        return foldByColumn(toCollection(iterable).toArray());
    }

    public <T> DataFrame objectsToRows(Iterable<T> objects, Function<T, Object[]> rowMapper) {
        DataFrameByRowBuilder byRowBuilder = new DataFrameByRowBuilder(columnsIndex);
        objects.forEach(o -> byRowBuilder.addRow(rowMapper.apply(o)));
        return byRowBuilder.create();
    }

    public DataFrame foldIntByColumn(int padWith, int... data) {

        FoldByColumnGeometry g = byColumnGeometry(data.length);

        int[][] columnarData = new int[g.width][g.height];

        for (int i = 0; i < g.fullColumns; i++) {
            System.arraycopy(data, i * g.height, columnarData[i], 0, g.height);
        }

        if (g.isLastColumnPartial()) {
            System.arraycopy(data, g.cellsInFullColumns(), columnarData[g.fullColumns], 0, g.partialColumnHeight);

            if (padWith != 0) {
                Arrays.fill(columnarData[g.fullColumns], g.partialColumnHeight, g.height, padWith);
            }
        }

        Series[] series = new Series[g.width];

        for (int i = 0; i < g.width; i++) {
            series[i] = new IntArraySeries(columnarData[i]);
        }

        return new ColumnDataFrame(columnsIndex, series);
    }

    public DataFrame foldIntStreamByRow(IntStream stream) {
        return foldIntStreamByRow(0, stream);
    }

    public DataFrame foldIntStreamByRow(int padWith, IntStream stream) {

        int width = columnsIndex.size();
        if (width == 0) {
            throw new IllegalArgumentException("Empty columns");
        }

        IntAccumulator[] columnBuilders = new IntAccumulator[width];
        for (int i = 0; i < width; i++) {
            columnBuilders[i] = new IntAccumulator();
        }

        PrimitiveIterator.OfInt it = stream.iterator();

        int p = 0;
        while (it.hasNext()) {
            columnBuilders[p % width].add(it.nextInt());
            p++;
        }

        // fill the last row to the end
        int pl = p % width;
        if (pl > 0) {
            for (; pl < width; pl++) {
                columnBuilders[pl].add(padWith);
            }
        }

        Series[] columnsData = new Series[width];
        for (int i = 0; i < width; i++) {
            columnsData[i] = columnBuilders[i].toIntSeries();
        }

        return new ColumnDataFrame(columnsIndex, columnsData);
    }

    public DataFrame foldIntStreamByColumn(IntStream stream) {
        return foldIntStreamByColumn(0, stream);
    }

    public DataFrame foldIntStreamByColumn(int padWith, IntStream stream) {
        // since we can't guess the height from the Stream, convert it to array and fold the array by column
        return foldIntByColumn(padWith, stream.toArray());
    }

    public DataFrame foldLongByColumn(long padWith, long... data) {

        FoldByColumnGeometry g = byColumnGeometry(data.length);

        long[][] columnarData = new long[g.width][g.height];

        for (int i = 0; i < g.fullColumns; i++) {
            System.arraycopy(data, i * g.height, columnarData[i], 0, g.height);
        }

        if (g.isLastColumnPartial()) {
            System.arraycopy(data, g.cellsInFullColumns(), columnarData[g.fullColumns], 0, g.partialColumnHeight);

            if (padWith != 0L) {
                Arrays.fill(columnarData[g.fullColumns], g.partialColumnHeight, g.height, padWith);
            }
        }

        Series[] series = new Series[g.width];

        for (int i = 0; i < g.width; i++) {
            series[i] = new LongArraySeries(columnarData[i]);
        }

        return new ColumnDataFrame(columnsIndex, series);
    }

    public DataFrame foldLongStreamByRow(LongStream stream) {
        return foldLongStreamByRow(0L, stream);
    }

    public DataFrame foldLongStreamByRow(long padWith, LongStream stream) {

        int width = columnsIndex.size();
        if (width == 0) {
            throw new IllegalArgumentException("Empty columns");
        }

        LongAccumulator[] columnBuilders = new LongAccumulator[width];
        for (int i = 0; i < width; i++) {
            columnBuilders[i] = new LongAccumulator();
        }

        PrimitiveIterator.OfLong it = stream.iterator();

        int p = 0;
        while (it.hasNext()) {
            columnBuilders[p % width].add(it.nextLong());
            p++;
        }

        // fill the last row to the end
        int pl = p % width;
        if (pl > 0) {
            for (; pl < width; pl++) {
                columnBuilders[pl].add(padWith);
            }
        }

        Series[] columnsData = new Series[width];
        for (int i = 0; i < width; i++) {
            columnsData[i] = columnBuilders[i].toLongSeries();
        }

        return new ColumnDataFrame(columnsIndex, columnsData);
    }

    public DataFrame foldLongStreamByColumn(LongStream stream) {
        return foldLongStreamByColumn(0L, stream);
    }

    public DataFrame foldLongStreamByColumn(long padWith, LongStream stream) {
        // since we can't guess the height from the Stream, convert it to array and fold the array by column
        return foldLongByColumn(padWith, stream.toArray());
    }

    public DataFrame foldDoubleByColumn(double padWith, double... data) {

        FoldByColumnGeometry g = byColumnGeometry(data.length);

        double[][] columnarData = new double[g.width][g.height];

        for (int i = 0; i < g.fullColumns; i++) {
            System.arraycopy(data, i * g.height, columnarData[i], 0, g.height);
        }

        if (g.isLastColumnPartial()) {
            System.arraycopy(data, g.cellsInFullColumns(), columnarData[g.fullColumns], 0, g.partialColumnHeight);

            if (padWith != 0.) {
                Arrays.fill(columnarData[g.fullColumns], g.partialColumnHeight, g.height, padWith);
            }
        }

        Series[] series = new Series[g.width];

        for (int i = 0; i < g.width; i++) {
            series[i] = new DoubleArraySeries(columnarData[i]);
        }

        return new ColumnDataFrame(columnsIndex, series);
    }

    public DataFrame foldDoubleStreamByRow(DoubleStream stream) {
        return foldDoubleStreamByRow(0., stream);
    }

    public DataFrame foldDoubleStreamByRow(double padWith, DoubleStream stream) {

        int width = columnsIndex.size();
        if (width == 0) {
            throw new IllegalArgumentException("Empty columns");
        }

        DoubleAccumulator[] columnBuilders = new DoubleAccumulator[width];
        for (int i = 0; i < width; i++) {
            columnBuilders[i] = new DoubleAccumulator();
        }

        PrimitiveIterator.OfDouble it = stream.iterator();

        int p = 0;
        while (it.hasNext()) {
            columnBuilders[p % width].add(it.nextDouble());
            p++;
        }

        // fill the last row to the end
        int pl = p % width;
        if (pl > 0) {
            for (; pl < width; pl++) {
                columnBuilders[pl].add(padWith);
            }
        }

        Series[] columnsData = new Series[width];
        for (int i = 0; i < width; i++) {
            columnsData[i] = columnBuilders[i].toDoubleSeries();
        }

        return new ColumnDataFrame(columnsIndex, columnsData);
    }

    public DataFrame foldDoubleStreamByColumn(DoubleStream stream) {
        return foldDoubleStreamByColumn(0., stream);
    }

    public DataFrame foldDoubleStreamByColumn(double padWith, DoubleStream stream) {
        // since we can't guess the height from the Stream, convert it to array and fold the array by column
        return foldDoubleByColumn(padWith, stream.toArray());
    }

    private <T> Collection<T> toCollection(Iterable<T> iterable) {

        if (iterable instanceof Collection) {
            return (Collection) iterable;
        }

        List<T> values = new ArrayList<>();
        iterable.forEach(values::add);
        return values;
    }

    protected DataFrame fromColumnarData(Object[][] columnarData) {

        int w = columnarData.length;
        Series[] series = new Series[w];

        for (int i = 0; i < w; i++) {
            series[i] = new ArraySeries(columnarData[i]);
        }

        return new ColumnDataFrame(columnsIndex, series);
    }

    FoldByColumnGeometry byColumnGeometry(int dataLength) {
        int w = columnsIndex.size();
        if (w == 0) {
            throw new IllegalArgumentException("Empty columns");
        }

        // check whether "dataLength" is partial or not against the width,
        // but calculate the las column offset against the height

        boolean partialLastColumn = dataLength % w > 0;
        int fullColumns = partialLastColumn
                ? w - 1
                : w;

        int h = partialLastColumn
                ? 1 + dataLength / w
                : dataLength / w;

        int partialColumnHeight = partialLastColumn ? dataLength % h : 0;
        
        return new FoldByColumnGeometry(w, h, partialColumnHeight, fullColumns);
    }

    private final class FoldByColumnGeometry {

        int width;
        int height;
        int fullColumns;
        int partialColumnHeight;

        FoldByColumnGeometry(int width, int height, int partialColumnHeight, int fullColumns) {
            this.width = width;
            this.height = height;
            this.fullColumns = fullColumns;
            this.partialColumnHeight = partialColumnHeight;
        }

        boolean isLastColumnPartial() {
            return partialColumnHeight > 0;
        }

        int cellsInFullColumns() {
            return fullColumns * height;
        }
    }
}
