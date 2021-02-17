package org.broadinstitute.hellbender.tools.walkers.sv;

import net.minidev.json.JSONArray;
import net.minidev.json.JSONObject;
import net.minidev.json.JSONValue;
import net.minidev.json.parser.ParseException;
import org.apache.commons.math3.util.FastMath;
import org.broadinstitute.hellbender.exceptions.GATKException;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Class to manage table with mixed columnar and matrix properties; with different primitive types. Supported types:
 *     boolean, int, long, float, double
 *
 * This class is useful for cases when the exact properties are not necessarily fixed in advance and it is desirable to
 * build records dynamically from (name, value) pairs.
 *
 * Conceptually the table is organized as numRows x numProperties x numColumns
 * Properties are stored as Object that can be cast to primitive arrays or array-of-arrays (matrix) in their original
 *     primitive type
 * Rows are extracted as float[] (suitable for machine learning), with one of two options
 *    normalize = false: translated to float type but otherwise unchanged from raw values
 *    normalize = true: shifted so the median is 0, and scaled so the standard deviation (over the central half of the
 *                      data) is 1.0. This is done on the fly when extracting rows.
 *                      The baseline and scale can be provided when adding a column (to provide consistency between
 *                      training and inference data) or computed automatically.
 */
class PropertiesTable implements Iterable<PropertiesTable.Property> {
    private static final String PROPERTY_NAMES_KEY = "propertyNames";
    private static final String PROPERTY_CLASSES_KEY = "propertyClasses";
    private static final String PROPERTY_BASELINE_KEY = "propertyBaseline";
    private static final String PROPERTY_SCALE_KEY = "propertyScale";
    private static final int DEFAULT_INITIAL_NUM_ALLOCATED_ROWS = 1000;
    private boolean allNumeric = true;

    @NotNull @Override public Iterator<Property> iterator() { return properties.iterator(); }


    enum PropertyClass {
        BooleanArrProperty, BooleanMatProperty,
        IntArrProperty, IntMatProperty,
        LongArrProperty, LongMatProperty,
        FloatArrProperty, FloatMatProperty,
        DoubleArrProperty, DoubleMatProperty,
        StringArrProperty, StringMatProperty,
        StringSetArrProperty, StringSetMatProperty,
    }

    private final List<Property> properties = new ArrayList<>();
    private final Map<String, List<String>> labelsEncoding = new HashMap<>();
    private float[] propertiesRow = null;
    private int initialNumAllocatedRows;

    PropertiesTable(final int initialNumAllocatedRows) { this.initialNumAllocatedRows = initialNumAllocatedRows; }
    PropertiesTable() { this(DEFAULT_INITIAL_NUM_ALLOCATED_ROWS); }

    public List<String> getPropertyNames() {
        return properties.stream().map(property -> property.name).collect(Collectors.toList());
    }

    protected int getPropertyIndex(final String propertyName) {
        return Collections.binarySearch(getPropertyNames(), propertyName);
    }

    public Property get(final String propertyName) {
        final int propertyIndex = getPropertyIndex(propertyName);
        final Property property;
        if(propertyIndex >= 0) {
            property = properties.get(propertyIndex);
        } else {
            throw new IllegalArgumentException("PropertiesTable does not contain " + propertyName);
        }
        return property;
    }

    public Property getOrCreateProperty(final String propertyName, final PropertyClass propertyClass) {
        return getOrCreateProperty(propertyName, propertyClass, initialNumAllocatedRows);
    }

    public Property getOrCreateProperty(final String propertyName, final PropertyClass propertyClass, final int numRows) {
        // Maintain propertyNames in sorted order, and keep other entries in same order
        final int propertyIndex = getPropertyIndex(propertyName);
        final Property property;
        if(propertyIndex >= 0) {
            property = properties.get(propertyIndex);
        } else {
            final int insertIndex = -1 - propertyIndex;
            property = Property.create(propertyClass, propertyName, numRows);
            if(!property.isNumeric()) {
                allNumeric = false;
            }
            properties.add(insertIndex, property);
        }
        return property;
    }

    public void setBaselineAndScale(final String propertyName, final PropertyClass propertyClass,
                                    final double baseline, final double scale) {
        getOrCreateProperty(propertyName, propertyClass).setBaselineAndScale(baseline, scale);
    }

    public void addLabelEncoding(final String propertyName, final List<String> propertyLabels) {
        labelsEncoding.put(propertyName, propertyLabels.stream().sorted().collect(Collectors.toList()));
    }

    public void remove(final String propertyName) {
        final int propertyIndex = getPropertyIndex(propertyName);
        if(propertyIndex < 0) {
            throw new IllegalArgumentException(propertyName + " is not contained in the PropertiesTable");
        }
        properties.remove(propertyIndex);
    }

    public void insert(final Property property) {
        final int propertyIndex = getPropertyIndex(property.name);
        if(!property.isNumeric()) {
            allNumeric = false;
        }
        if (propertyIndex >= 0) {
            final Property oldProperty = properties.get(propertyIndex);
            if(oldProperty.getNumRows() > 0) {
                throw new IllegalArgumentException("PropertiesTable already contains property: " + property.name);
            } else {
                // property exists but it's empty. If it has useful baseline and scale information, transfer those,
                // then overwrite with new property
                if(oldProperty.normalizationIsSet()) {
                    property.setBaselineAndScale(oldProperty.getBaseline(), oldProperty.getScale());
                }
                properties.set(propertyIndex, property);
            }
        } else {
            final int insertIndex = -1 - propertyIndex;
            properties.add(insertIndex, property);
        }
    }

    /**
     * "clear" existing data by setting numRows to 0. Property types and memory allocation is not altered so that any
     * subsequent data can be written to first row without continual reallocation
     */
    public void clearRows() {
        setNumRows(0);
    }

    public void setNumRows(final int numRows) {
        for(final Property property : properties) {
            property.numRows = numRows;
        }
    }

    public void setNumAllocatedRows(final int numRows) {
        this.initialNumAllocatedRows = numRows;
        for(final Property property : properties) {
            property.setAllocatedRows(numRows);
        }
    }

    public void append(final String propertyName, final boolean value) {
        getOrCreateProperty(propertyName, PropertyClass.BooleanArrProperty).append(value);
    }
    public void append(final String propertyName, final boolean[] value) {
        getOrCreateProperty(propertyName, PropertyClass.BooleanMatProperty).append(value);
    }
    public void append(final String propertyName, final int value) {
        getOrCreateProperty(propertyName, PropertyClass.IntArrProperty).append(value);
    }
    public void append(final String propertyName, final int[] value) {
        getOrCreateProperty(propertyName, PropertyClass.IntMatProperty).append(value);
    }
    public void append(final String propertyName, final long value) {
        getOrCreateProperty(propertyName, PropertyClass.LongArrProperty).append(value);
    }
    public void append(final String propertyName, final long[] value) {
        getOrCreateProperty(propertyName, PropertyClass.LongMatProperty).append(value);
    }
    public void append(final String propertyName, final float value) {
        getOrCreateProperty(propertyName, PropertyClass.FloatArrProperty).append(value);
    }
    public void append(final String propertyName, final float[] value) {
        getOrCreateProperty(propertyName, PropertyClass.FloatMatProperty).append(value);
    }
    public void append(final String propertyName, final double value) {
        getOrCreateProperty(propertyName, PropertyClass.DoubleArrProperty).append(value);
    }
    public void append(final String propertyName, final double[] value) {
        getOrCreateProperty(propertyName, PropertyClass.DoubleMatProperty).append(value);
    }
    public void append(final String propertyName, final String value) {
        getOrCreateProperty(propertyName, PropertyClass.StringArrProperty).append(value);
    }
    public void append(final String propertyName, final String[] value) {
        getOrCreateProperty(propertyName, PropertyClass.StringMatProperty).append(value);
    }
    public void append(final String propertyName, final Set<String> value) {
        getOrCreateProperty(propertyName, PropertyClass.StringSetArrProperty).append(value);
    }
    public void append(final String propertyName, final Set<String>[] value) {
        getOrCreateProperty(propertyName, PropertyClass.StringSetMatProperty).append(value);
    }

    public void set(final String propertyName, final boolean[] values) {
        getOrCreateProperty(propertyName, PropertyClass.BooleanArrProperty).set(values);
    }
    public void set(final String propertyName, final boolean[][] values) {
        getOrCreateProperty(propertyName, PropertyClass.BooleanMatProperty).set(values);
    }
    public void set(final String propertyName, final int[] values) {
        getOrCreateProperty(propertyName, PropertyClass.IntArrProperty).set(values);
    }
    public void set(final String propertyName, final int[][] values) {
        getOrCreateProperty(propertyName, PropertyClass.IntMatProperty).set(values);
    }
    public void set(final String propertyName, final long[] values) {
        getOrCreateProperty(propertyName, PropertyClass.LongArrProperty).set(values);
    }
    public void set(final String propertyName, final long[][] values) {
        getOrCreateProperty(propertyName, PropertyClass.LongMatProperty).set(values);
    }
    public void set(final String propertyName, final float[] values) {
        getOrCreateProperty(propertyName, PropertyClass.FloatArrProperty).set(values);
    }
    public void set(final String propertyName, final float[][] values) {
        getOrCreateProperty(propertyName, PropertyClass.FloatMatProperty).set(values);
    }
    public void set(final String propertyName, final double[] values) {
        getOrCreateProperty(propertyName, PropertyClass.DoubleArrProperty).set(values);
    }
    public void set(final String propertyName, final double[][] values) {
        getOrCreateProperty(propertyName, PropertyClass.DoubleMatProperty).set(values);
    }
    public void set(final String propertyName, final String[] values) {
        getOrCreateProperty(propertyName, PropertyClass.StringArrProperty).set(values);
    }
    public void set(final String propertyName, final String[][] values) {
        getOrCreateProperty(propertyName, PropertyClass.StringMatProperty).set(values);
    }
    public void set(final String propertyName, final Set<String>[] values) {
        getOrCreateProperty(propertyName, PropertyClass.StringSetArrProperty).set(values);
    }
    public void set(final String propertyName, final Set<String>[][] values) {
        getOrCreateProperty(propertyName, PropertyClass.StringSetMatProperty).set(values);
    }

    protected int getConsistentIntPropertyValue(ToIntFunction<Property> method, final String valuesLabel) {
        final int[] distinctValues =properties.stream()
                .mapToInt(method)
                .filter(c -> c >= 0)
                .distinct()
                .toArray();
        switch(distinctValues.length) {
            case 0: // no values
                return -1;
            case 1: // consistent value
                return distinctValues[0];
            default: // inconsistent values
                throw new IllegalArgumentException("PropertiesTable contains inconsistent numbers of " + valuesLabel);
        }
    }

    public int getNumColumns() {
        return getConsistentIntPropertyValue(Property::getNumColumns, "columns");
    }

    public int getNumRows() {
        return getConsistentIntPropertyValue(Property::getNumRows, "rows");
    }

    public int getNumProperties() {
        if(allNumeric) {
            return properties.size();
        }
        return properties.stream()
                .mapToInt(
                        property -> {
                            final List<String> allLabels = labelsEncoding.containsKey(property.name) ?
                                    labelsEncoding.get(property.name) :
                                    property.getAllLabels();
                            return allLabels == null ?
                                    1 :
                                    allLabels.size() > 2 ?
                                            allLabels.size() :
                                            allLabels.size() == 2 ? 1 : 0;
                        }
                )
                .sum();
    }

    protected void trim() {
        // save memory by right-sizing the dynamically allocated arrays
        for(final Property property : properties){
            property.setAllocatedRows(property.getNumRows());
        }
    }

    protected void oneHot() {
        for(final Property property : new ArrayList<>(properties)) {
            final List<String> allLabels = labelsEncoding.containsKey(property.name) ?
                labelsEncoding.get(property.name) :
                property.getAllLabels();
            if(allLabels != null) {
                remove(property.name);
                for(final Property oneHotProperty : property.oneHot(allLabels)) {
                    insert(oneHotProperty);
                }
            }
        }
        allNumeric = true;
    }

    protected void setBaselineAndScales() {
        int numNormalizationsCalculated = 0;
        for(final Property property : properties) {
            if(!property.normalizationIsSet()) {
                property.calculateBaselineAndScale();
                ++numNormalizationsCalculated;
            }
        }
        if(numNormalizationsCalculated != 0 && numNormalizationsCalculated != properties.size()) {
            throw new IllegalArgumentException("Some but not all properties have baseline and scale set.");
        }
    }

    /**
     * Once all data has been added to the table
     *   1) check to make sure number of rows and columns are consistent
     *   2) right-size memory (match number of allocated and actual rows)
     *   3) one-hot encode any label properties
     *   4) ensure that baseline and scale are appropriately set for every Property
     *   5) allocate float[] propertiesRow so that rows can be efficiently queried for inference
     */
    public void validateAndFinalize() {
        getNumColumns();
        getNumRows();
        trim();
        oneHot();
        setBaselineAndScales();
        propertiesRow = new float[getNumProperties()];
    }

    /**
     * Copy table data from specified row into prepared array buffer
     * @param outArray array buffer to copy into
     * @param outIndex offset of buffer to start copying
     * @param rowIndex requested row from table
     * @param columnIndex requested column from table (properties without columns will ignore this value)
     * @param normalize if true, normalize (stable z-score) data, if false, copy raw data
     * @return next outIndex for writing
     */
    public int copyPropertiesRow(final float[] outArray, int outIndex,
                                 final int rowIndex, final int columnIndex, final boolean normalize) {
        for(final Property property : properties) {
            outArray[outIndex] = property.getAsFloat(rowIndex, columnIndex, normalize);
            ++outIndex;
        }
        return outIndex;
    }

    public float[] getPropertiesRow(final int rowIndex, final int columnIndex, final boolean normalize) {
        copyPropertiesRow(propertiesRow, 0, rowIndex, columnIndex, normalize);
        return propertiesRow;
    }

    public Map<String, List<String>> getLabelsEncoding() {
        // don't want anyone messing with this, just want to expose the information
        return Collections.unmodifiableMap(labelsEncoding);
    }

    protected void saveNormalizationStats(final OutputStream outputStream) {
        final JSONArray propNames = new JSONArray();
        final JSONArray propClasses = new JSONArray();
        final JSONArray propBase = new JSONArray();
        final JSONArray propScale = new JSONArray();
        for (final Property property : properties) {
            propNames.add(property.name);
            propClasses.add(property.getClass().getSimpleName());
            propBase.add(property.baseline);
            propScale.add(property.scale);
        }
        final JSONObject jsonObject = new JSONObject();
        jsonObject.put(PROPERTY_NAMES_KEY, propNames);
        jsonObject.put(PROPERTY_CLASSES_KEY, propClasses);
        jsonObject.put(PROPERTY_BASELINE_KEY, propBase);
        jsonObject.put(PROPERTY_SCALE_KEY, propScale);

        try {
            outputStream.write(jsonObject.toJSONString().getBytes());
        } catch(IOException ioException) {
            throw new GATKException("Error saving data summary json", ioException);
        }
    }

    protected void saveLabelsEncoding(final OutputStream outputStream) {
        final JSONObject jsonObject = new JSONObject();
        for(Map.Entry<String, List<String>> encodingEntry : labelsEncoding.entrySet()) {
            final JSONArray labels = new JSONArray();
            labels.addAll(encodingEntry.getValue());
            jsonObject.put(encodingEntry.getKey(), labels);
        }
        try {
            outputStream.write(jsonObject.toJSONString().getBytes());
        } catch(IOException ioException) {
            throw new GATKException("Error saving data summary json", ioException);
        }
    }

    public void saveDataEncoding(final OutputStream outputStream) throws IOException {
        saveNormalizationStats(outputStream);
        outputStream.write("\n".getBytes());
        saveLabelsEncoding(outputStream);
    }

    protected static long getNumTrue(final boolean[] values) {
        return getNumTrue(values, values.length);
    }
    protected static long getNumTrue(final boolean[] values, final int numRows) {
        long numTrue = 0;
        for(int i = 0; i < numRows; ++i) {
            if(values[i]) {
                ++numTrue;
            }
        }
        return numTrue;
    }

    protected static double getBaselineOrdered(final double[] orderedValues) {
        // get baseline as median of values
        return orderedValues.length == 0 ?
                0 :
                orderedValues.length % 2 == 1 ?
                        orderedValues[orderedValues.length / 2] :
                        (orderedValues[orderedValues.length / 2 - 1] + orderedValues[orderedValues.length / 2]) / 2.0;
    }

    protected static double getScaleOrdered(final double[] orderedValues, final double baseline) {
        // get scale as root-mean-square difference from baseline, over central half of data (to exclude outliers)
        switch(orderedValues.length) {
            case 0:
            case 1:
                return 1.0;
            default:
                final int start = orderedValues.length / 4;
                final int stop = 3 * orderedValues.length / 4;
                double scale = 0.0;
                for(int idx = start; idx < stop; ++idx) {
                    scale += (orderedValues[idx] - baseline) * (orderedValues[idx] - baseline);
                }
                return FastMath.max(FastMath.sqrt(scale / (1 + stop - start)), 1.0e-6);
        }
    }

    protected static double getDoubleFromJSON(final Object jsonObject) {
        if(jsonObject instanceof Double) {
            return (Double) jsonObject;
        } else if(jsonObject instanceof BigDecimal) {
            return ((BigDecimal)jsonObject).doubleValue();
        } else {
            throw new GATKException("Unknown conversion to double for " + jsonObject.getClass().getName());
        }
    }

    protected void loadNormalizations(final InputStream inputStream) {
        final JSONObject jsonObject;
        try {
            jsonObject = (JSONObject) JSONValue.parseWithException(inputStream);
        } catch (IOException | ParseException ioException) {
            throw new GATKException("Unable to parse JSON from inputStream", ioException);
        }
        final JSONArray propNames = ((JSONArray) jsonObject.get(PROPERTY_NAMES_KEY));
        final JSONArray propClasses = ((JSONArray) jsonObject.get(PROPERTY_CLASSES_KEY));
        final JSONArray propBase = ((JSONArray) jsonObject.get(PROPERTY_BASELINE_KEY));
        final JSONArray propScale = ((JSONArray) jsonObject.get(PROPERTY_SCALE_KEY));
        for (int idx = 0; idx < propNames.size(); ++idx) {
            setBaselineAndScale(
                    (String)propNames.get(idx), PropertyClass.valueOf((String)propClasses.get(idx)),
                    getDoubleFromJSON(propBase.get(idx)), getDoubleFromJSON(propScale.get(idx))
            );
        }
    }

    protected void loadEncodings(final InputStream inputStream) {
        final JSONObject jsonObject;
        try {
            jsonObject = (JSONObject) JSONValue.parseWithException(inputStream);
        } catch (IOException | ParseException ioException) {
            throw new GATKException("Unable to parse JSON from inputStream", ioException);
        }
        for(final String propertyName : jsonObject.keySet()) {
            final JSONArray labels = (JSONArray) jsonObject.get(propertyName);
            addLabelEncoding(propertyName, labels.stream().map(label -> (String)label).collect(Collectors.toList()));
        }
    }

    public void loadDataEncoding(final InputStream inputStream) {
        loadNormalizations(inputStream);
        loadEncodings(inputStream);
    }

    static abstract class Property {
        public final String name;
        private Float baseline = null;
        private Float scale = null;
        protected int numRows;

        static final int ALLOCATION_GROWTH_SCALE = 2;

        Property(final String name) { this.name = name; }

        abstract void set(final Object values);
        abstract public float getAsFloat(final int rowIndex, final int hyperIndex);
        abstract protected int getAllocatedRows();
        abstract public void setAllocatedRowsUnguarded(final int numRows);
        abstract protected void assignNextValue(final Object value);
        abstract protected double[] getValuesAsOrderedDoubles();

        public Float getBaseline() { return baseline; }
        public Float getScale() { return scale; }
        public boolean normalizationIsSet() {
            if(baseline == null || scale == null) {
                if(!(baseline == null && scale == null)) {
                    throw new IllegalArgumentException("baseline or scale assigned a null value");
                }
                return false;
            } else {
                return true;
            }
        }
        public boolean isNumeric() { return true; }
        public List<String> getAllLabels() { return null; }
        public List<Property> oneHot(final List<String> allLabels) { return null; }

        public static Property create(final PropertyClass propertyClass, final String name) {
            return create(propertyClass, name, DEFAULT_INITIAL_NUM_ALLOCATED_ROWS);
        }

        public static Property create(final PropertyClass propertyClass, final String name, final int numRows) {
            switch(propertyClass) {
                case BooleanArrProperty: return new BooleanArrProperty(name, numRows);
                case BooleanMatProperty: return new BooleanMatProperty(name, numRows);
                case IntArrProperty: return new IntArrProperty(name, numRows);
                case IntMatProperty: return new IntMatProperty(name, numRows);
                case LongArrProperty: return new LongArrProperty(name, numRows);
                case LongMatProperty: return new LongMatProperty(name, numRows);
                case FloatArrProperty: return new FloatArrProperty(name, numRows);
                case FloatMatProperty: return new FloatMatProperty(name, numRows);
                case DoubleArrProperty: return new DoubleArrProperty(name, numRows);
                case DoubleMatProperty: return new DoubleMatProperty(name, numRows);
                case StringArrProperty: return new StringArrProperty(name, numRows);
                case StringMatProperty: return new StringMatProperty(name, numRows);
                case StringSetArrProperty: return new StringSetArrProperty(name, numRows);
                case StringSetMatProperty: return new StringSetMatProperty(name, numRows);
                default: throw new IllegalArgumentException("Unable to create Property of type " + propertyClass);
            }
        }

        public int getNumColumns() {
            final int[] numColumns = getArrayOfDistinctNumColumns();
            switch(numColumns.length) {
                case 0: // no data in property or an Array property
                    return -1;
                case 1: // normal case for matrix property
                    return numColumns[0];
                default: // error, inconsistent numbers of columns
                    throw new IllegalArgumentException("Inconsistent number of columns in matrix property: " + name);
            }
        }
        protected int[] getArrayOfDistinctNumColumns() { return new int[0]; }

        public int getNumRows() { return numRows; }

        public void setAllocatedRows(final int numRows) {
            if(numRows <= getNumRows()) {
                if(numRows < getNumRows()) {
                    throw new IllegalArgumentException("Can't shrink allocated memory for property to less than used space.");
                }
            } else {
                setAllocatedRowsUnguarded(numRows);
            }
        }

        public float getAsFloat(final int rowIndex, final int hyperIndex, final boolean normalize) {
            final float rawValue = getAsFloat(rowIndex, hyperIndex);
            return normalize ?
                    (rawValue - baseline) / scale :
                    rawValue;
        }
        public boolean getAsBool(final int rowIndex, final int hyperIndex) {
            throw new IllegalArgumentException("getAsBool() not defined for Property of type " + this.getClass().getSimpleName());
        }
        public int getAsInt(final int rowIndex, final int hyperIndex) {
            throw new IllegalArgumentException("getAsInt() not defined for Property of type " + this.getClass().getSimpleName());
        }
        public long getAsLong(final int rowIndex, final int hyperIndex) {
            throw new IllegalArgumentException("getAsLong() not defined for Property of type " + this.getClass().getSimpleName());
        }

        public void append(final Object value) {
            if(getNumRows() >= getAllocatedRows()) {
                setAllocatedRows(FastMath.max(DEFAULT_INITIAL_NUM_ALLOCATED_ROWS, getNumRows() * ALLOCATION_GROWTH_SCALE));
            }
            assignNextValue(value);
            ++this.numRows;
        }

        public void setBaselineAndScale(final double baseline, final double scale) {
            this.baseline = (float)baseline;
            this.scale = (float)scale;
        }

        protected void calculateBaselineAndScale() {
            final double[] orderedValues = getValuesAsOrderedDoubles();
            final double baseline = getBaselineOrdered(orderedValues);
            this.baseline = (float)baseline;
            scale = (float)getScaleOrdered(orderedValues, baseline);
        }
    }

    static public class BooleanArrProperty extends Property {
        private boolean[] values;

        BooleanArrProperty(final String name, final int numValues) { super(name); values = new boolean[numValues]; }
        BooleanArrProperty(final String name) { this(name, DEFAULT_INITIAL_NUM_ALLOCATED_ROWS); }

        @Override public void set(Object values) { this.values = (boolean[]) values; }
        @Override public float getAsFloat(final int rowIndex, final int hyperIndex) {
            return values[rowIndex] ? 1F : 0F;
        }
        @Override public boolean getAsBool(final int rowIndex, final int hyperIndex) { return values[rowIndex]; }
        @Override public int getAllocatedRows() { return values.length; }
        @Override public void setAllocatedRowsUnguarded(final int numRows) {
            this.values = Arrays.copyOf(this.values, numRows);
        }
        @Override protected void assignNextValue(final Object value) { this.values[numRows] = (boolean)value; }
        @Override protected void calculateBaselineAndScale() {
            // special case for booleans
            final long numTrue = getNumTrue(values, numRows);
            final double baseline = numTrue / (double) numRows;
            final double scale = numTrue == 0 || numTrue == numRows ?
                    1.0 :
                    FastMath.sqrt(baseline * (1.0 - baseline));
            setBaselineAndScale(baseline, scale);
        }
        @Override protected double[] getValuesAsOrderedDoubles() {
            // special case for booleans
            throw new GATKException("Method not needed for booleans");
        }
    }

    static public class BooleanMatProperty extends Property {
        private boolean[][] values;

        BooleanMatProperty(final String name, final int numValues) { super(name); values = new boolean[numValues][]; }
        BooleanMatProperty(final String name) { this(name, DEFAULT_INITIAL_NUM_ALLOCATED_ROWS); }

        @Override public void set(Object values) { this.values = (boolean[][]) values; }
        @Override public float getAsFloat(final int rowIndex, final int hyperIndex) {
            return values[rowIndex][hyperIndex] ? 1F : 0F;
        }
        @Override public boolean getAsBool(final int rowIndex, final int hyperIndex) {
            return this.values[rowIndex][hyperIndex];
        }
        @Override protected int[] getArrayOfDistinctNumColumns() {
            return IntStream.range(0, numRows).map(i -> values[i].length).distinct().toArray();
        }
        @Override public int getAllocatedRows() { return values.length; }
        @Override public void setAllocatedRowsUnguarded(final int numRows) {
            this.values = Arrays.copyOf(this.values, numRows);
        }
        @Override protected void assignNextValue(final Object value) { this.values[numRows] = (boolean[])value; }
        @Override protected void calculateBaselineAndScale() {
            // special case for booleans
            final long numTrue = IntStream.range(0, numRows).mapToLong(i -> getNumTrue(values[i])).sum();
            final double baseline = numTrue / (double) numRows;
            final double scale = numTrue == 0 || numTrue == numRows ?
                    1.0 :
                    FastMath.sqrt(baseline * (1.0 - baseline));
            setBaselineAndScale(baseline, scale);
        }
        @Override protected double[] getValuesAsOrderedDoubles() {
            // special case for booleans
            throw new GATKException("Method not needed for booleans");
        }
    }

    static public class IntArrProperty extends Property {
        private int[] values;

        IntArrProperty(final String name, final int numValues) { super(name); values = new int[numValues]; }
        IntArrProperty(final String name) { this(name, DEFAULT_INITIAL_NUM_ALLOCATED_ROWS); }

        @Override public void set(Object values) { this.values = (int[]) values; }
        @Override public float getAsFloat(final int rowIndex, final int hyperIndex) { return values[rowIndex]; }
        @Override public int getAsInt(final int rowIndex, final int hyperIndex) { return this.values[rowIndex]; }
        @Override public int getAllocatedRows() { return values.length; }
        @Override public void setAllocatedRowsUnguarded(final int numRows) {
            this.values = Arrays.copyOf(this.values, numRows);
        }
        @Override protected void assignNextValue(final Object value) { this.values[numRows] = (int)value; }
        @Override protected double[] getValuesAsOrderedDoubles() {
            return Arrays.stream(values, 0, numRows).sorted().mapToDouble(x -> x).toArray();
        }
    }

    static public class IntMatProperty extends Property {
        private int[][] values;

        IntMatProperty(final String name, final int numValues) { super(name); values = new int[numValues][]; }
        IntMatProperty(final String name) { this(name, DEFAULT_INITIAL_NUM_ALLOCATED_ROWS); }

        @Override public void set(Object values) { this.values = (int[][]) values; }
        @Override public float getAsFloat(final int rowIndex, final int hyperIndex) {
            return values[rowIndex][hyperIndex];
        }
        @Override public int getAsInt(final int rowIndex, final int hyperIndex) {
            return this.values[rowIndex][hyperIndex];
        }
        @Override protected int[] getArrayOfDistinctNumColumns() {
            return IntStream.range(0, numRows).map(i -> values[i].length).distinct().toArray();
        }
        @Override public int getAllocatedRows() { return values.length; }
        @Override public void setAllocatedRowsUnguarded(final int numRows) {
            this.values = Arrays.copyOf(this.values, numRows);
        }
        @Override protected void assignNextValue(final Object value) { this.values[numRows] = (int[])value; }
        @Override protected double[] getValuesAsOrderedDoubles() {
            return Arrays.stream(values, 0, numRows).flatMapToInt(Arrays::stream).sorted()
                .mapToDouble(x -> x).toArray();
        }
    }

    static public class LongArrProperty extends Property {
        private long[] values;

        LongArrProperty(final String name, final int numValues) { super(name); values = new long[numValues]; }
        LongArrProperty(final String name) { this(name, DEFAULT_INITIAL_NUM_ALLOCATED_ROWS); }

        @Override public void set(Object values) { this.values = (long[]) values; }
        @Override public float getAsFloat(final int rowIndex, final int hyperIndex) { return values[rowIndex]; }
        @Override public long getAsLong(final int rowIndex, final int hyperIndex) { return this.values[rowIndex]; }
        @Override public int getAllocatedRows() { return values.length; }
        @Override public void setAllocatedRowsUnguarded(final int numRows) {
            this.values = Arrays.copyOf(this.values, numRows);
        }
        @Override protected void assignNextValue(final Object value) { this.values[numRows] = (long)value; }
        @Override protected double[] getValuesAsOrderedDoubles() {
            return Arrays.stream(values, 0, numRows).sorted().mapToDouble(x -> x).toArray();
        }
    }

    static public class LongMatProperty extends Property {
        private long[][] values;

        LongMatProperty(final String name, final int numValues) { super(name); values = new long[numValues][]; }
        LongMatProperty(final String name) { this(name, DEFAULT_INITIAL_NUM_ALLOCATED_ROWS); }

        @Override public void set(Object values) { this.values = (long[][]) values; }
        @Override public float getAsFloat(final int rowIndex, final int hyperIndex) {
            return values[rowIndex][hyperIndex];
        }
        @Override public long getAsLong(final int rowIndex, final int hyperIndex) {
            return this.values[rowIndex][hyperIndex];
        }
        @Override protected int[] getArrayOfDistinctNumColumns() {
            return IntStream.range(0, numRows).map(i -> values[i].length).distinct().toArray();
        }
        @Override public int getAllocatedRows() { return values.length; }
        @Override public void setAllocatedRowsUnguarded(final int numRows) {
            this.values = Arrays.copyOf(this.values, numRows);
        }
        @Override protected void assignNextValue(final Object value) { this.values[numRows] = (long[])value; }
        @Override protected double[] getValuesAsOrderedDoubles() {
            return Arrays.stream(values, 0, numRows).flatMapToLong(Arrays::stream).sorted()
                .mapToDouble(x -> x).toArray();
        }
    }

    static public class FloatArrProperty extends Property {
        private float[] values;

        FloatArrProperty(final String name, final int numValues) { super(name); values = new float[numValues]; }
        FloatArrProperty(final String name) { this(name, DEFAULT_INITIAL_NUM_ALLOCATED_ROWS); }

        @Override public void set(Object values) { this.values = (float[]) values; }
        @Override public float getAsFloat(final int rowIndex, final int hyperIndex) { return values[rowIndex]; }
        @Override public int getAllocatedRows() { return values.length; }
        @Override public void setAllocatedRowsUnguarded(final int numRows) {
            this.values = Arrays.copyOf(this.values, numRows);
        }
        @Override protected void assignNextValue(final Object value) { this.values[numRows] = (float)value; }
        @Override protected double[] getValuesAsOrderedDoubles() {
            return IntStream.range(0, numRows).mapToDouble(i -> (double)values[i]).sorted().toArray();
        }
    }

    static public class FloatMatProperty extends Property {
        private float[][] values;

        FloatMatProperty(final String name, final int numValues) { super(name); values = new float[numValues][]; }
        FloatMatProperty(final String name) { this(name, DEFAULT_INITIAL_NUM_ALLOCATED_ROWS); }

        @Override public void set(Object values) { this.values = (float[][]) values; }
        @Override public float getAsFloat(final int rowIndex, final int hyperIndex) {
            return values[rowIndex][hyperIndex];
        }
        @Override protected int[] getArrayOfDistinctNumColumns() {
            return IntStream.range(0, numRows).map(i -> values[i].length).distinct().toArray();
        }
        @Override public int getAllocatedRows() { return values.length; }
        @Override public void setAllocatedRowsUnguarded(final int numRows) {
            this.values = Arrays.copyOf(this.values, numRows);
        }
        @Override protected void assignNextValue(final Object value) { this.values[numRows] = (float[])value; }
        @Override protected double[] getValuesAsOrderedDoubles() {
            return Arrays.stream(values, 0, numRows)
                .flatMapToDouble(arr -> IntStream.range(0, arr.length).mapToDouble(i -> arr[i]))
                .sorted()
                .toArray();
        }
    }

    static public class DoubleArrProperty extends Property {
        private double[] values;

        DoubleArrProperty(final String name, final int numValues) { super(name); values = new double[numValues]; }
        DoubleArrProperty(final String name) { this(name, DEFAULT_INITIAL_NUM_ALLOCATED_ROWS); }

        @Override public void set(Object values) { this.values = (double[]) values; }
        @Override public float getAsFloat(final int rowIndex, final int hyperIndex) { return (float)values[rowIndex]; }
        @Override public int getAllocatedRows() { return values.length; }
        @Override public void setAllocatedRowsUnguarded(final int numRows) {
            this.values = Arrays.copyOf(this.values, numRows);
        }
        @Override protected void assignNextValue(final Object value) { this.values[numRows] = (double)value; }
        @Override protected double[] getValuesAsOrderedDoubles() {
            return Arrays.stream(values, 0, numRows).sorted().toArray();
        }
    }

    static public class DoubleMatProperty extends Property {
        private double[][] values;

        DoubleMatProperty(final String name, final int numValues) { super(name); values = new double[numValues][]; }
        DoubleMatProperty(final String name) { this(name, DEFAULT_INITIAL_NUM_ALLOCATED_ROWS); }

        @Override public void set(Object values) { this.values = (double[][]) values; }
        @Override public float getAsFloat(final int rowIndex, final int hyperIndex) {
            return (float)values[rowIndex][hyperIndex];
        }
        @Override protected int[] getArrayOfDistinctNumColumns() {
            return IntStream.range(0, numRows).map(i -> values[i].length).distinct().toArray();
        }
        @Override public int getAllocatedRows() { return values.length; }
        @Override public void setAllocatedRowsUnguarded(final int numRows) {
            this.values = Arrays.copyOf(this.values, numRows);
        }
        @Override protected void assignNextValue(final Object value) { this.values[numRows] = (double[])value; }
        @Override protected double[] getValuesAsOrderedDoubles() {
            return Arrays.stream(values, 0, numRows).flatMapToDouble(Arrays::stream).sorted().toArray();
        }
    }

    static public class StringArrProperty extends Property {
        private String[] values;

        StringArrProperty(final String name, final int numValues) { super(name); values = new String[numValues]; }
        StringArrProperty(final String name) { this(name, DEFAULT_INITIAL_NUM_ALLOCATED_ROWS); }

        @Override public void set(Object values) { this.values = (String[]) values; }
        @Override public float getAsFloat(final int rowIndex, final int hyperIndex) {
            throw new IllegalArgumentException("String properties cannot be converted to Float, they must be one-hot-encoded");
        }
        @Override public int getAllocatedRows() { return values.length; }
        @Override public void setAllocatedRowsUnguarded(final int numRows) {
            this.values = Arrays.copyOf(this.values, numRows);
        }
        @Override protected void assignNextValue(final Object value) { this.values[numRows] = (String)value; }
        @Override protected double[] getValuesAsOrderedDoubles() {
            throw new IllegalArgumentException("String properties cannot be converted to double, they must be one-hot-encoded");
        }
        @Override public boolean isNumeric() { return false; }
        @Override public List<String> getAllLabels() {
            return Arrays.stream(values, 0, numRows).distinct().sorted().collect(Collectors.toList());
        }
        @Override public List<Property> oneHot(final List<String> allLabels) {
            switch(allLabels.size()) {
                case 0:
                case 1:  // no actual property, return empty non-null List<Property>
                    return new ArrayList<>();
                case 2:  // have a single new boolean property
                    final String trueLabel = allLabels.get(1);
                    final BooleanArrProperty boolProperty = new BooleanArrProperty(trueLabel, numRows);
                    for(int row = 0; row < numRows; ++row) {
                        boolProperty.append(this.values[row].equals(trueLabel));
                    }
                    return Collections.singletonList(boolProperty);
                default:  // have new one-hot encoded set of multiple properties
                    final int numProperties = allLabels.size();
                    final List<Property> properties = IntStream.range(0, numProperties)
                        .mapToObj(i -> new BooleanArrProperty(allLabels.get(i), numRows))
                        .collect(Collectors.toList());
                    for(int row = 0; row < numRows; ++row) {
                        final String label = this.values[row];
                        for(int propertyIndex = 0; propertyIndex < numProperties; ++propertyIndex) {
                            properties.get(propertyIndex).append(label.equals(allLabels.get(propertyIndex)));
                        }
                    }
                    return properties;
            }
        }
    }

    static public class StringMatProperty extends Property {
        private String[][] values;

        StringMatProperty(final String name, final int numValues) { super(name); values = new String[numValues][]; }
        StringMatProperty(final String name) { this(name, DEFAULT_INITIAL_NUM_ALLOCATED_ROWS); }

        @Override public void set(Object values) { this.values = (String[][]) values; }
        @Override public float getAsFloat(final int rowIndex, final int hyperIndex) {
            throw new IllegalArgumentException("String properties cannot be converted to Float, they must be one-hot-encoded");
        }
        @Override protected int[] getArrayOfDistinctNumColumns() {
            return IntStream.range(0, numRows).map(i -> values[i].length).distinct().toArray();
        }
        @Override public int getAllocatedRows() { return values.length; }
        @Override public void setAllocatedRowsUnguarded(final int numRows) {
            this.values = Arrays.copyOf(this.values, numRows);
        }
        @Override protected void assignNextValue(final Object value) { this.values[numRows] = (String[])value; }
        @Override protected double[] getValuesAsOrderedDoubles() {
            throw new IllegalArgumentException("String properties cannot be converted to double, they must be one-hot-encoded");
        }
        @Override public boolean isNumeric() { return false; }
        @Override public List<String> getAllLabels() {
            return Arrays.stream(values, 0, numRows).flatMap(Arrays::stream).distinct().sorted()
                .collect(Collectors.toList());
        }
        @Override public List<Property> oneHot(final List<String> allLabels) {
            switch(allLabels.size()) {
                case 0:
                case 1:  // no actual property, return empty non-null List<Property>
                    return new ArrayList<>();
                case 2:  // have a single new boolean property
                    final String trueLabel = allLabels.get(1);
                    final BooleanMatProperty boolProperty = new BooleanMatProperty(trueLabel, numRows);
                    for(int row = 0; row < numRows; ++row) {
                        final String[] colLabels = this.values[row];
                        final boolean[] colBooleans = new boolean[colLabels.length];
                        for(int col = 0; col < colLabels.length; ++col) {
                            colBooleans[col] = colLabels[col].equals(trueLabel);
                        }
                        boolProperty.append(colBooleans);
                    }
                    return Collections.singletonList(boolProperty);
                default:  // have new one-hot encoded set of multiple properties
                    final int numProperties = allLabels.size();
                    final List<Property> properties = IntStream.range(0, numProperties)
                            .mapToObj(i -> new BooleanMatProperty(allLabels.get(i), numRows))
                            .collect(Collectors.toList());
                    for(int row = 0; row < numRows; ++row) {
                        final String[] colLabels = this.values[row];
                        for(int propertyIndex = 0; propertyIndex < numProperties; ++propertyIndex) {
                            final boolean[] colBooleans = new boolean[colLabels.length];
                            final String propertyLabel = allLabels.get(propertyIndex);;
                            for(int col = 0; col < colLabels.length; ++col) {
                                colBooleans[col] = colLabels[col].equals(propertyLabel);
                            }
                            properties.get(propertyIndex).append(colBooleans);
                        }
                    }
                    return properties;
            }
        }
    }

    @SuppressWarnings("unchecked")
    static public class StringSetArrProperty extends Property {
        // Note: can't store as Set<String>[] because:
        // A) it may be called upon to add UnmodifiableSet<String>
        // B) that class is private, so values must be initialized as a HashSet<?>
        // C) the resulting conflict causes an ArrayStoreException
        private Object[] values;

        StringSetArrProperty(final String name, final int numValues) {
            super(name); values = new Object[numValues];
        }
        StringSetArrProperty(final String name) { this(name, DEFAULT_INITIAL_NUM_ALLOCATED_ROWS); }

        @Override public void set(Object values) { this.values = (Object[]) values; }
        @Override public float getAsFloat(final int rowIndex, final int hyperIndex) {
            throw new IllegalArgumentException("String properties cannot be converted to Float, they must be one-hot-encoded");
        }
        @Override public int getAllocatedRows() { return values.length; }
        @Override public void setAllocatedRowsUnguarded(final int numRows) {
            this.values = Arrays.copyOf(this.values, numRows);
        }
        @Override protected void assignNextValue(final Object value) { this.values[numRows] = value; }
        @Override protected double[] getValuesAsOrderedDoubles() {
            throw new IllegalArgumentException("String properties cannot be converted to double, they must be one-hot-encoded");
        }
        @Override public boolean isNumeric() { return false; }
        @Override public List<String> getAllLabels() {
            return Arrays.stream(values, 0, numRows).flatMap(o -> ((Set<String>)o).stream())
                .distinct().sorted().collect(Collectors.toList());
        }
        @Override public List<Property> oneHot(final List<String> allLabels) {
            switch(allLabels.size()) {
                case 0:
                case 1:  // no actual property, return empty non-null List<Property>
                    return new ArrayList<>();
                case 2:  // have a single new boolean property
                    final String trueLabel = allLabels.get(1);
                    final BooleanArrProperty boolProperty = new BooleanArrProperty(trueLabel, numRows);
                    for(int row = 0; row < numRows; ++row) {
                        boolProperty.append(((Set<String>)this.values[row]).contains(trueLabel));
                    }
                    return Collections.singletonList(boolProperty);
                default:  // have new one-hot encoded set of multiple properties
                    final int numProperties = allLabels.size();
                    final List<Property> properties = IntStream.range(0, numProperties)
                            .mapToObj(i -> new BooleanArrProperty(allLabels.get(i), numRows))
                            .collect(Collectors.toList());
                    for(int row = 0; row < numRows; ++row) {
                        final Set<String> rowLabels = (Set<String>)this.values[row];
                        for(int propertyIndex = 0; propertyIndex < numProperties; ++propertyIndex) {
                            properties.get(propertyIndex).append(rowLabels.contains(allLabels.get(propertyIndex)));
                        }
                    }
                    return properties;
            }
        }
    }

    @SuppressWarnings("unchecked")
    static public class StringSetMatProperty extends Property {
        private Set<String>[][] values;

        StringSetMatProperty(final String name, final int numValues) {
            super(name); values = (Set<String>[][])new HashSet<?>[numValues][];
        }
        StringSetMatProperty(final String name) { this(name, DEFAULT_INITIAL_NUM_ALLOCATED_ROWS); }

        @Override public void set(Object values) { this.values = (Set<String>[][]) values; }
        @Override public float getAsFloat(final int rowIndex, final int hyperIndex) {
            throw new IllegalArgumentException("String properties cannot be converted to Float, they must be one-hot-encoded");
        }
        @Override protected int[] getArrayOfDistinctNumColumns() {
            return IntStream.range(0, numRows).map(i -> values[i].length).distinct().toArray();
        }
        @Override public int getAllocatedRows() { return values.length; }
        @Override public void setAllocatedRowsUnguarded(final int numRows) {
            this.values = Arrays.copyOf(this.values, numRows);
        }
        @Override protected void assignNextValue(final Object value) { this.values[numRows] = (Set<String>[])value; }
        @Override protected double[] getValuesAsOrderedDoubles() {
            throw new IllegalArgumentException("String properties cannot be converted to double, they must be one-hot-encoded");
        }
        @Override public boolean isNumeric() { return false; }
        @Override public List<String> getAllLabels() {
            return Arrays.stream(values, 0, numRows).flatMap(Arrays::stream)
                .flatMap(Collection::stream).distinct().sorted().collect(Collectors.toList());
        }
        @Override public List<Property> oneHot(final List<String> allLabels) {
            switch(allLabels.size()) {
                case 0:
                case 1:  // no actual property, return empty non-null List<Property>
                    return new ArrayList<>();
                case 2:  // have a single new boolean property
                    final String trueLabel = allLabels.get(1);
                    final BooleanMatProperty boolProperty = new BooleanMatProperty(trueLabel, numRows);
                    for(int row = 0; row < numRows; ++row) {
                        final Set<String>[] colLabels = this.values[row];
                        final boolean[] colBooleans = new boolean[colLabels.length];
                        for(int col = 0; col < colLabels.length; ++col) {
                            colBooleans[col] = colLabels[col].contains(trueLabel);
                        }
                        boolProperty.append(colBooleans);
                    }
                    return Collections.singletonList(boolProperty);
                default:  // have new one-hot encoded set of multiple properties
                    final int numProperties = allLabels.size();
                    final List<Property> properties = IntStream.range(0, numProperties)
                            .mapToObj(i -> new BooleanMatProperty(allLabels.get(i), numRows))
                            .collect(Collectors.toList());
                    for(int row = 0; row < numRows; ++row) {
                        final Set<String>[] colLabels = this.values[row];
                        for(int propertyIndex = 0; propertyIndex < numProperties; ++propertyIndex) {
                            final boolean[] colBooleans = new boolean[colLabels.length];
                            final String propertyLabel = allLabels.get(propertyIndex);;
                            for(int col = 0; col < colLabels.length; ++col) {
                                colBooleans[col] = colLabels[col].contains(propertyLabel);
                            }
                            properties.get(propertyIndex).append(colBooleans);
                        }
                    }
                    return properties;
            }
        }
    }
}