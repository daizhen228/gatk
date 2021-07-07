package org.broadinstitute.hellbender.tools.sv.cluster;

import htsjdk.samtools.SAMSequenceDictionary;
import htsjdk.variant.variantcontext.StructuralVariantType;
import org.broadinstitute.hellbender.tools.sv.SVCallRecord;
import org.broadinstitute.hellbender.utils.IntervalUtils;
import org.broadinstitute.hellbender.utils.SimpleInterval;
import org.broadinstitute.hellbender.utils.Utils;

import java.util.Collection;
import java.util.Set;

/**
 * <p>Main class for SV clustering. Two items are clustered together if they:</p>
 * <ul>
 *   <li>Match event types</li>
 *   <li>Match strands</li>
 *   <li>Match contigs</li>
 *   <li>Meet minimum interval reciprocal overlap (unless inter-chromosomal or a BND).</li>
 *   <li>Meet minimum sample reciprocal overlap using carrier status. Genotypes (GT fields) are used to determine carrier
 *   status first. If not found and the event is of type DEL or DUP, then copy number (CN fields) are used instead.
 *   In the latter case, it is expected that ploidy can be determined from the number of entries in GT.</li>
 *   <li>Start and end coordinates are within a defined distance.</li>
 * </ul>
 *
 * <p>Interval overlap, sample overlap, and coordinate proximity parameters are defined separately for depth-only/depth-only,
 * depth-only/PESR, and PESR/PESR item pairs using the {@link ClusteringParameters} class. Note that all criteria must be met for
 * two candidate items to cluster, unless the comparison is depth-only/depth-only, in which case only one of
 * interval overlap or break-end proximity must be met. For insertions with undefined length (SVLEN less than 1), interval
 * overlap is tested assuming the given start position and a length of
 * {@value CanonicalSVLinkage#INSERTION_ASSUMED_LENGTH_FOR_OVERLAP}.</p>
 */
public class CanonicalSVLinkage<T extends SVCallRecord> implements SVClusterLinkage<T> {

    protected final SAMSequenceDictionary dictionary;
    protected final boolean enableCNV;  // DEL/DUP multi-allelic site clustering
    protected ClusteringParameters depthOnlyParams;
    protected ClusteringParameters mixedParams;
    protected ClusteringParameters evidenceParams;

    public static final int INSERTION_ASSUMED_LENGTH_FOR_OVERLAP = 50;

    public static final double DEFAULT_RECIPROCAL_OVERLAP_DEPTH_ONLY = 0.8;
    public static final int DEFAULT_WINDOW_DEPTH_ONLY = 0;
    public static final double DEFAULT_SAMPLE_OVERLAP_DEPTH_ONLY = 0;

    public static final double DEFAULT_RECIPROCAL_OVERLAP_MIXED = 0.8;
    public static final int DEFAULT_WINDOW_MIXED = 1000;
    public static final double DEFAULT_SAMPLE_OVERLAP_MIXED = 0;

    public static final double DEFAULT_RECIPROCAL_OVERLAP_PESR = 0.5;
    public static final int DEFAULT_WINDOW_PESR = 500;
    public static final double DEFAULT_SAMPLE_OVERLAP_PESR = 0;

    public static final ClusteringParameters DEFAULT_DEPTH_ONLY_PARAMS =
            ClusteringParameters.createDepthParameters(
                    DEFAULT_RECIPROCAL_OVERLAP_DEPTH_ONLY,
                    DEFAULT_WINDOW_DEPTH_ONLY,
                    DEFAULT_SAMPLE_OVERLAP_DEPTH_ONLY);
    public static final ClusteringParameters DEFAULT_MIXED_PARAMS =
            ClusteringParameters.createMixedParameters(
                    DEFAULT_RECIPROCAL_OVERLAP_MIXED,
                    DEFAULT_WINDOW_MIXED,
                    DEFAULT_SAMPLE_OVERLAP_MIXED);
    public static final ClusteringParameters DEFAULT_PESR_PARAMS =
            ClusteringParameters.createPesrParameters(
                    DEFAULT_RECIPROCAL_OVERLAP_PESR,
                    DEFAULT_WINDOW_PESR,
                    DEFAULT_SAMPLE_OVERLAP_PESR);

    /**
     * Create a new engine
     * @param dictionary sequence dictionary pertaining to clustered records
     * @param enableCNV enables clustering of DEL/DUP variants into CNVs
     */
    public CanonicalSVLinkage(final SAMSequenceDictionary dictionary,
                              final boolean enableCNV) {
        Utils.nonNull(dictionary);
        this.dictionary = dictionary;
        this.depthOnlyParams = DEFAULT_DEPTH_ONLY_PARAMS;
        this.mixedParams = DEFAULT_MIXED_PARAMS;
        this.evidenceParams = DEFAULT_PESR_PARAMS;
        this.enableCNV = enableCNV;
    }

    @Override
    public boolean areClusterable(final SVCallRecord a, final SVCallRecord b) {
        if (a.getType() != b.getType()) {
            if (!enableCNV) {
                // CNV clustering disabled, so no type mixing
                return false;
            } else if (!(a.isCNV() && b.isCNV())) {
                // CNV clustering enabled, but at least one was not a CNV type
                return false;
            }
        }
        // Strands match
        if (a.getStrandA() != b.getStrandA() || a.getStrandB() != b.getStrandB()) {
            return false;
        }
        // Checks appropriate parameter set
        if (evidenceParams.isValidPair(a, b)) {
            return clusterTogetherWithParams(a, b, evidenceParams, dictionary);
        } else if (mixedParams.isValidPair(a, b)) {
            return clusterTogetherWithParams(a, b, mixedParams, dictionary);
        } else if (depthOnlyParams.isValidPair(a, b)) {
            return clusterTogetherWithParams(a, b, depthOnlyParams, dictionary);
        } else {
            return false;
        }
    }

    /**
     * Checks for minimum fractional sample overlap of the two sets. Defaults to true if both sets are empty.
     */
    private static boolean hasSampleSetOverlap(final Set<String> samplesA, final Set<String> samplesB, final double minSampleOverlap) {
        final int denom = Math.max(samplesA.size(), samplesB.size());
        if (denom == 0) {
            return true;
        }
        final double sampleOverlap = getSampleSetOverlap(samplesA, samplesB) / (double) denom;
        return sampleOverlap >= minSampleOverlap;
    }

    /**
     * Returns number of overlapping items
     */
    private static int getSampleSetOverlap(final Collection<String> a, final Set<String> b) {
        return (int) a.stream().filter(b::contains).count();
    }

    /**
     * Returns true if there is sufficient fractional carrier sample overlap in the two records.
     */
    protected static boolean hasSampleOverlap(final SVCallRecord a, final SVCallRecord b, final double minSampleOverlap) {
        if (minSampleOverlap > 0) {
            final Set<String> samplesA = a.getCarrierSamples();
            final Set<String> samplesB = b.getCarrierSamples();
            return hasSampleSetOverlap(samplesA, samplesB, minSampleOverlap);
        } else {
            return true;
        }
    }

    /**
     * Helper function to test for clustering between two items given a particular set of parameters.
     * Note that the records have already been subjected to the checks in {@link #areClusterable(SVCallRecord, SVCallRecord)}.
     */
    private static boolean clusterTogetherWithParams(final SVCallRecord a, final SVCallRecord b,
                                                     final ClusteringParameters params,
                                                     final SAMSequenceDictionary dictionary) {

        // Contigs match
        if (!(a.getContigA().equals(b.getContigA()) && a.getContigB().equals(b.getContigB()))) {
            return false;
        }

        // Reciprocal overlap
        // Check bypassed if both are inter-chromosomal
        if (a.isIntrachromosomal()) {
            final SimpleInterval intervalA = new SimpleInterval(a.getContigA(), a.getPositionA(), a.getPositionA() + getLengthForOverlap(a) - 1);
            final SimpleInterval intervalB = new SimpleInterval(b.getContigA(), b.getPositionA(), b.getPositionA() + getLengthForOverlap(b) - 1);
            final boolean hasReciprocalOverlap = IntervalUtils.isReciprocalOverlap(intervalA, intervalB, params.getReciprocalOverlap());
            if (params.requiresOverlapAndProximity() && !hasReciprocalOverlap) {
                return false;
            } else if (!params.requiresOverlapAndProximity() && hasReciprocalOverlap) {
                return true;
            }
        }

        // Breakend proximity
        final SimpleInterval intervalA1 = a.getPositionAInterval().expandWithinContig(params.getWindow(), dictionary);
        final SimpleInterval intervalA2 = a.getPositionBInterval().expandWithinContig(params.getWindow(), dictionary);
        final SimpleInterval intervalB1 = b.getPositionAInterval();
        final SimpleInterval intervalB2 = b.getPositionBInterval();
        if (!(intervalA1.overlaps(intervalB1) && intervalA2.overlaps(intervalB2))) {
            return false;
        }

        // Sample overlap (possibly the least scalable check)
        return hasSampleOverlap(a, b, params.getSampleOverlap());
    }

    /**
     * Gets event length used for overlap testing.
     */
    private static int getLengthForOverlap(final SVCallRecord record) {
        Utils.validate(record.isIntrachromosomal(), "Record even must be intra-chromosomal");
        if (record.getType() == StructuralVariantType.INS) {
            return record.getLength() < 1 ? INSERTION_ASSUMED_LENGTH_FOR_OVERLAP : record.getLength();
        } else {
            // TODO lengths less than 1 shouldn't be valid
            return Math.max(record.getLength(), 1);
        }
    }

    @Override
    public int getMaxClusterableStartingPosition(final SVCallRecord record) {
        return Math.max(
                getMaxClusterableStartingPositionWithParams(record, record.isDepthOnly() ? depthOnlyParams : evidenceParams, dictionary),
                getMaxClusterableStartingPositionWithParams(record, mixedParams, dictionary)
        );
    }

    /**
     * Returns max feasible start position of an item clusterable with the given record, given a set of clustering parameters.
     */
    private static int getMaxClusterableStartingPositionWithParams(final SVCallRecord call,
                                                                   final ClusteringParameters params,
                                                                   final SAMSequenceDictionary dictionary) {
        final String contig = call.getContigA();
        final int contigLength = dictionary.getSequence(contig).getSequenceLength();
        // Reciprocal overlap window
        final int maxPositionByOverlap;
        if (call.isIntrachromosomal()) {
            final int maxPosition = (int) (call.getPositionA() + (1.0 - params.getReciprocalOverlap()) * getLengthForOverlap(call));
            maxPositionByOverlap = Math.min(maxPosition, contigLength);
        } else {
            maxPositionByOverlap = call.getPositionA();
        }

        // Breakend proximity window
        final int maxPositionByWindow = Math.min(call.getPositionA() + params.getWindow(), contigLength);

        if (params.requiresOverlapAndProximity()) {
            return Math.min(maxPositionByOverlap, maxPositionByWindow);
        } else {
            return Math.max(maxPositionByOverlap, maxPositionByWindow);
        }
    }

    public final ClusteringParameters getDepthOnlyParams() {
        return depthOnlyParams;
    }
    public final ClusteringParameters getMixedParams() {
        return mixedParams;
    }
    public final ClusteringParameters getEvidenceParams() {
        return evidenceParams;
    }

    public final void setDepthOnlyParams(ClusteringParameters depthOnlyParams) { this.depthOnlyParams = depthOnlyParams; }

    public final void setMixedParams(ClusteringParameters mixedParams) {
        this.mixedParams = mixedParams;
    }

    public final void setEvidenceParams(ClusteringParameters evidenceParams) {
        this.evidenceParams = evidenceParams;
    }

}