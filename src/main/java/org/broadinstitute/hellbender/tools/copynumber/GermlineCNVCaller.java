package org.broadinstitute.hellbender.tools.copynumber;

import org.broadinstitute.barclay.argparser.Advanced;
import org.broadinstitute.barclay.argparser.Argument;
import org.broadinstitute.barclay.argparser.ArgumentCollection;
import org.broadinstitute.barclay.argparser.CommandLineProgramProperties;
import org.broadinstitute.barclay.help.DocumentedFeature;
import org.broadinstitute.hellbender.cmdline.CommandLineProgram;
import org.broadinstitute.hellbender.cmdline.StandardArgumentDefinitions;
import org.broadinstitute.hellbender.cmdline.argumentcollections.IntervalArgumentCollection;
import org.broadinstitute.hellbender.cmdline.argumentcollections.OptionalIntervalArgumentCollection;
import org.broadinstitute.hellbender.cmdline.programgroups.CopyNumberProgramGroup;
import org.broadinstitute.hellbender.exceptions.UserException;
import org.broadinstitute.hellbender.tools.copynumber.arguments.CopyNumberArgumentValidationUtils;
import org.broadinstitute.hellbender.tools.copynumber.arguments.CopyNumberStandardArgument;
import org.broadinstitute.hellbender.tools.copynumber.arguments.GermlineCNVHybridADVIArgumentCollection;
import org.broadinstitute.hellbender.tools.copynumber.arguments.GermlineCallingArgumentCollection;
import org.broadinstitute.hellbender.tools.copynumber.arguments.GermlineDenoisingModelArgumentCollection;
import org.broadinstitute.hellbender.tools.copynumber.formats.collections.AnnotatedIntervalCollection;
import org.broadinstitute.hellbender.tools.copynumber.formats.collections.SimpleIntervalCollection;
import org.broadinstitute.hellbender.utils.Utils;
import org.broadinstitute.hellbender.utils.io.IOUtils;
import org.broadinstitute.hellbender.utils.io.Resource;
import org.broadinstitute.hellbender.utils.python.PythonScriptExecutor;
import org.broadinstitute.hellbender.utils.runtime.ProcessOutput;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import static org.broadinstitute.hellbender.tools.copynumber.arguments.CopyNumberArgumentValidationUtils.streamOfSubsettedAndValidatedReadCounts;

/**
 * Calls copy-number variants in germline samples given their counts and the corresponding output of
 * {@link DetermineGermlineContigPloidy}. The former should be either HDF5 or TSV count files generated by
 * {@link CollectReadCounts}; TSV files may be compressed (e.g., with bgzip),
 * but must then have filenames ending with the extension .gz.  See the documentation for the {@code input} argument
 * for details on enabling streaming of indexed count files from Google Cloud Storage.
 *
 * <h3>Introduction</h3>
 *
 * <p>Reliable detection of copy-number variation (CNV) from read-depth ("coverage" or "counts") data such as whole
 * exome sequencing (WES), whole genome sequencing (WGS), and custom gene sequencing panels requires a comprehensive
 * model to account for technical variation in library preparation and sequencing. The Bayesian model and the associated
 * inference scheme implemented in {@link GermlineCNVCaller} includes provisions for inferring and explaining away much
 * of the technical variation. Furthermore, CNV calling confidence is automatically adjusted for each sample and
 * genomic interval.
 *
 * <p>The parameters of the probabilistic model for read-depth bias and variance estimation (hereafter, "the coverage
 * model") can be automatically inferred by {@link GermlineCNVCaller} by providing a cohort of germline samples
 * sequenced using the same sequencing platform and library preparation protocol (in case of WES, the same capture
 * kit). We refer to this run mode of the tool as the <b>COHORT</b> mode. The number of samples required for the
 * COHORT mode depends on several factors such as the sequencing depth, tissue type/quality and similarity in the cohort,
 * and the stringency of following the library preparation and sequencing protocols. For WES and WGS samples, we
 * recommend including at least 30 samples.</p>
 *
 * <p>The parametrized coverage model can be used for CNV calling on future case samples provided that they are
 * strictly compatible with the cohort used to generate the model parameters (in terms of tissue type(s), library
 * preparation and sequencing protocols). We refer to this mode as the <b>CASE</b> run mode. There is no lower
 * limit on the number of samples for running {@link GermlineCNVCaller} in CASE mode.</p>
 *
 * <p>In both tool run modes, {@link GermlineCNVCaller} requires karyotyping and global read-depth information for
 * all samples. Such information can be automatically generated by running {@link DetermineGermlineContigPloidy}
 * on all samples, and passed on to {@link GermlineCNVCaller} by providing the ploidy output calls using the argument
 * {@code contig-ploidy-calls}. The ploidy state of a contig is used as the <em>baseline</em>
 * ("default") copy-number state of all intervals contained in that contig for the corresponding sample. All other
 * copy-number states are treated as alternative states and get equal shares from the total alternative state
 * probability (set using the {@code p-alt} argument).</p>
 *
 * <p>Note that in rare cases the inference process can diverge which will lead to an error on the Python side.
 * In such cases the inference will be automatically restarted one more time with a new random seed. If inference
 * diverges the second time we suggest checking if input count or karyotype values or other inputs are abnormal
 * (an example of abnormality is a count file containing mostly zeros). </p>
 *
 * <h3>Python environment setup</h3>
 *
 * <p>The computation done by this tool, aside from input data parsing and validation, is performed outside of the Java
 * Virtual Machine and using the <em>gCNV computational python module</em>, namely {@code gcnvkernel}. It is crucial that
 * the user has properly set up a python conda environment with {@code gcnvkernel} and its dependencies
 * installed. If the user intends to run {@link GermlineCNVCaller} using one of the official GATK Docker images,
 * the python environment is already set up. Otherwise, the environment must be created and activated as described in the
 * main GATK README.md file.</p>
 *
 * <p>Advanced users may wish to set the <code>THEANO_FLAGS</code> environment variable to override the GATK theano
 * configuration. For example, by running
 * <code>THEANO_FLAGS="base_compiledir=PATH/TO/BASE_COMPILEDIR" gatk GermlineCNVCaller ...</code>, users can specify
 * the theano compilation directory (which is set to <code>$HOME/.theano</code> by default).  See theano documentation
 * at <a href="https://theano-pymc.readthedocs.io/en/latest/library/config.html">
 *     https://theano-pymc.readthedocs.io/en/latest/library/config.html</a>.
 * </p>
 *
 * <h3>Tool run modes</h3>
 * <dl>
 *     <dt>COHORT mode:</dt>
 *     <dd><p>The tool will be run in COHORT mode using the argument {@code run-mode COHORT}.
 *      In this mode, coverage model parameters are inferred simultaneously with the CNV states. Depending on
 *      available memory, it may be necessary to run the tool over a subset of all intervals, which can be specified
 *      by -L; this can be used to pass a filtered interval list produced by {@link FilterIntervals} to mask
 *      intervals from modeling. The specified intervals must be present in all of the input count files. The output
 *      will contain two subdirectories, one ending with "-model" and the other with "-calls".</p>
 *
 *      <p>The model subdirectory contains a snapshot of the inferred parameters of the coverage model, which may be
 *      used later for CNV calling in one or more similarly-sequenced samples as mentioned earlier. Optionally, the path
 *      to a previously obtained coverage model parametrization can be provided via the {@code model} argument
 *      in COHORT mode, in which case, the provided parameters will be only used for model initialization and
 *      a new parametrization will be generated based on the input count files. Furthermore, the genomic intervals are
 *      set to those used in creating the previous parametrization and interval-related arguments will be ignored.
 *      Note that the newly obtained parametrization ultimately reflects the input count files from the last run,
 *      regardless of whether or not an initialization parameter set is given. If the users wishes to model coverage
 *      data from two or more cohorts simultaneously, all of the input counts files must be given to the tool at once.
 *
 *      <p>The calls subdirectory contains sequentially-ordered subdirectories for each sample, each listing various
 *      sample-specific estimated quantities such as the probability of various copy-number states for each interval,
 *      a parametrization of the GC curve, sample-specific unexplained variance, read-depth, and loadings of
 *      coverage bias factors.</p></dd>
 *
 *     <dt>CASE mode:</dt>
 *     <dd><p>The tool will be run in CASE mode using the argument {@code run-mode CASE}. The path to a previously
 *     obtained model directory must be provided via the {@code model} argument in this mode. The modeled intervals are
 *     then specified by a file contained in the model directory, all interval-related arguments are ignored in this
 *     mode, and all model intervals must be present in all of the input count files. The tool output in CASE mode
 *     is only the "-calls" subdirectory and is organized similarly to that in COHORT mode.</p>
 *
 *      <p>Note that at the moment, this tool does not automatically verify the compatibility of the provided parametrization
 *      with the provided count files. Model compatibility may be assessed a posteriori by inspecting the magnitude of
 *      sample-specific unexplained variance of each sample, and asserting that they lie within the same range as
 *      those obtained from the cohort used to generate the parametrization. This manual step is expected to be made
 *      automatic in the future.</p>
 * </dl>
 *
 * <h3>Important Remarks</h3>
 * <dl>
 *     <dt>Choice of hyperparameters:</dt>
 *     <dd><p>The quality of coverage model parametrization and the sensitivity/precision of germline CNV calling are
 *     sensitive to the choice of model hyperparameters, including the prior probability of alternative copy-number states
 *     (set using the {@code p-alt} argument), prevalence of active (i.e. CNV-rich) intervals (set via the
 *     {@code p-active} argument), the coherence length of CNV events and active/silent domains
 *     across intervals (set using the {@code cnv-coherence-length} and {@code class-coherence-length} arguments,
 *     respectively), and the typical scale of interval- and sample-specific unexplained variance
 *     (set using the {@code interval-psi-scale} and {@code sample-psi-scale} arguments, respectively). It is crucial
 *     to note that these hyperparameters are <em>not</em> universal and must be tuned for each sequencing protocol
 *     and properly set at runtime.</p></dd>
 *
 *     <dt>Running {@link GermlineCNVCaller} on a subset of intervals:</dt>
 *     <dd><p>As mentioned earlier, it may be necessary to run the tool over a subset of all intervals depending on
 *     available memory. The number of intervals must be large enough to include a genomically diverse set of regions
 *     for reliable inference of the GC bias curve, as well as other bias factors. For WES and WGS, we recommend no less
 *     than 10000 consecutive intervals spanning at least 10 - 50 mb.</p></dd>
 *
 *     <dt>Memory requirements for the python subprocess ("gcnvkernel"):</dt>
 *     <dd><p>The computation done by this tool, for the most part, is performed outside of JVM and via a spawned
 *     python subprocess. The Java heap memory is only used for loading sample counts and preparing raw data for the
 *     python subprocess. The user must ensure that the machine has enough free physical memory for spawning and executing
 *     the python subprocess. Generally speaking, the resource requirements of this tool scale linearly with each of the
 *     number of samples, the number of modeled intervals, the highest copy number state, the number of bias factors, and
 *     the number of knobs on the GC curve. For example, the python subprocess requires approximately 16GB of physical memory
 *     for modeling 10000 intervals for 100 samples, with 16 maximum bias factors, maximum copy-number state of 10, and
 *     explicit GC bias modeling.</p></dd>
 * </dl>
 *
 * <h3>Usage examples</h3>
 *
 * <p>COHORT mode:</p>
 * <pre>
 * gatk GermlineCNVCaller \
 *   --run-mode COHORT \
 *   -L intervals.interval_list \
 *   --interval-merging-rule OVERLAPPING_ONLY \
 *   --contig-ploidy-calls path_to_contig_ploidy_calls \
 *   --input normal_1.counts.hdf5 \
 *   --input normal_2.counts.hdf5 \
 *   ... \
 *   --output output_dir \
 *   --output-prefix normal_cohort_run
 * </pre>
 *
 * <p>CASE mode:</p>
 * <pre>
 * gatk GermlineCNVCaller \
 *   --run-mode CASE \
 *   --contig-ploidy-calls path_to_contig_ploidy_calls \
 *   --model previous_model_path \
 *   --input normal_1.counts.hdf5 \
 *   ... \
 *   --output output_dir \
 *   --output-prefix normal_case_run
 * </pre>
 *
 * @author Mehrtash Babadi &lt;mehrtash@broadinstitute.org&gt;
 * @author Samuel Lee &lt;slee@broadinstitute.org&gt;
 */
@CommandLineProgramProperties(
        summary = "Calls copy-number variants in germline samples given their counts and " +
                "the output of DetermineGermlineContigPloidy",
        oneLineSummary = "Calls copy-number variants in germline samples given their counts and " +
                "the output of DetermineGermlineContigPloidy",
        programGroup = CopyNumberProgramGroup.class
)
@DocumentedFeature
public final class GermlineCNVCaller extends CommandLineProgram {
    public enum RunMode {
        COHORT, CASE
    }

    public static final String COHORT_DENOISING_CALLING_PYTHON_SCRIPT = "cohort_denoising_calling.py";
    public static final String CASE_SAMPLE_CALLING_PYTHON_SCRIPT = "case_denoising_calling.py";

    //name of the interval file output by the python code in the model directory
    public static final String INPUT_MODEL_INTERVAL_FILE = "interval_list.tsv";

    public static final String MODEL_PATH_SUFFIX = "-model";
    public static final String CALLS_PATH_SUFFIX = "-calls";
    public static final String TRACKING_PATH_SUFFIX = "-tracking";

    public static final String CONTIG_PLOIDY_CALLS_DIRECTORY_LONG_NAME = "contig-ploidy-calls";
    public static final String RUN_MODE_LONG_NAME = "run-mode";

    // Starting gCNV random seed
    private static final int STARTING_SEED = 1984;

    // Default exit code output by gCNV python indicating divergence error; needs to be in sync with the corresponding gcnvkernel constant
    private static final int DIVERGED_INFERENCE_EXIT_CODE = 239;

    @Argument(
            doc = "Input paths for read-count files containing integer read counts in genomic intervals for all samples.  " +
                    "All intervals specified via -L/-XL must be contained; " +
                    "if none are specified, then intervals must be identical and in the same order for all samples.  " +
                    "If read-count files are given by Google Cloud Storage paths, " +
                    "have the extension .counts.tsv or .counts.tsv.gz, " +
                    "and have been indexed by IndexFeatureFile, " +
                    "only the specified intervals will be queried and streamed; " +
                    "this can reduce disk usage by avoiding the complete localization of all read-count files.",
            fullName = StandardArgumentDefinitions.INPUT_LONG_NAME,
            shortName = StandardArgumentDefinitions.INPUT_SHORT_NAME,
            minElements = 1
    )
    private List<String> inputReadCountPaths = new ArrayList<>();

    @Argument(
            doc = "Tool run-mode.",
            fullName = RUN_MODE_LONG_NAME
    )
    private RunMode runMode;

    @Argument(
            doc = "Input contig-ploidy calls directory (output of DetermineGermlineContigPloidy).",
            fullName = CONTIG_PLOIDY_CALLS_DIRECTORY_LONG_NAME
    )
    private File inputContigPloidyCallsDir;

    @Argument(
            doc = "Input denoising-model directory. In COHORT mode, this argument is optional; if provided," +
                    "a new model will be built using this input model to initialize. In CASE mode, " +
                    "this argument is required and the denoising model parameters are set to this input model.",
            fullName = CopyNumberStandardArgument.MODEL_LONG_NAME,
            optional = true
    )
    private File inputModelDir = null;

    @Argument(
            doc = "Input annotated-intervals file containing annotations for GC content in genomic intervals " +
                    "(output of AnnotateIntervals).  All intervals specified via -L must be contained.  " +
                    "This input should not be provided if an input denoising-model directory is given (the latter " +
                    "already contains the annotated-interval file).",
            fullName = CopyNumberStandardArgument.ANNOTATED_INTERVALS_FILE_LONG_NAME,
            optional = true
    )
    private File inputAnnotatedIntervalsFile = null;

    @Argument(
            doc = "Prefix for output filenames.",
            fullName =  CopyNumberStandardArgument.OUTPUT_PREFIX_LONG_NAME
    )
    private String outputPrefix;

    @Argument(
            doc = "Output directory.  This will be created if it does not exist.",
            fullName =  StandardArgumentDefinitions.OUTPUT_LONG_NAME,
            shortName = StandardArgumentDefinitions.OUTPUT_SHORT_NAME
    )
    private File outputDir;

    @ArgumentCollection
    protected IntervalArgumentCollection intervalArgumentCollection
            = new OptionalIntervalArgumentCollection();

    @Advanced
    @ArgumentCollection
    private GermlineDenoisingModelArgumentCollection germlineDenoisingModelArgumentCollection =
            new GermlineDenoisingModelArgumentCollection();

    @Advanced
    @ArgumentCollection
    private GermlineCallingArgumentCollection germlineCallingArgumentCollection
            = new GermlineCallingArgumentCollection();

    @Advanced
    @ArgumentCollection
    private GermlineCNVHybridADVIArgumentCollection germlineCNVHybridADVIArgumentCollection
            = new GermlineCNVHybridADVIArgumentCollection();

    private SimpleIntervalCollection specifiedIntervals;
    private File specifiedIntervalsFile;

    @Override
    protected void onStartup() {
        /* check for successful import of gcnvkernel */
        PythonScriptExecutor.checkPythonEnvironmentForPackage("gcnvkernel");
    }

    @Override
    protected Object doWork() {
        validateArguments();

        resolveIntervals();

        //read in count files, validate they contain specified subset of intervals, and output
        //count files for these intervals to temporary files
        final List<File> intervalSubsetReadCountFiles = writeIntervalSubsetReadCountFiles();

        final String script = (runMode == RunMode.COHORT) ? COHORT_DENOISING_CALLING_PYTHON_SCRIPT : CASE_SAMPLE_CALLING_PYTHON_SCRIPT;

        //call python inference code
        final PythonScriptExecutor executor = new PythonScriptExecutor(true);
        ProcessOutput pythonProcessOutput = executor.executeScriptAndGetOutput(
                new Resource(script, GermlineCNVCaller.class),
                null,
                composePythonArguments(intervalSubsetReadCountFiles, STARTING_SEED));
        if (pythonProcessOutput.getExitValue() != 0) {
            // We restart once if the inference diverged
            if (pythonProcessOutput.getExitValue() == DIVERGED_INFERENCE_EXIT_CODE) {
                final Random generator = new Random(STARTING_SEED);
                final int nextGCNVSeed = generator.nextInt();
                logger.info("The inference failed to converge and will be restarted once with a different random seed.");
                pythonProcessOutput = executor.executeScriptAndGetOutput(
                        new Resource(script, GermlineCNVCaller.class),
                        null,
                        composePythonArguments(intervalSubsetReadCountFiles, nextGCNVSeed));
            } else {
                throw executor.getScriptException(executor.getExceptionMessageFromScriptError(pythonProcessOutput));
            }
        }

        if (pythonProcessOutput.getExitValue() != 0) {
            if (pythonProcessOutput.getExitValue() == DIVERGED_INFERENCE_EXIT_CODE) {
                logger.info("The inference failed to converge twice. We suggest checking if input count or karyotype" +
                        " values or other inputs are abnormal (an example of abnormality is a count file containing mostly zeros).");
            }
            throw executor.getScriptException(executor.getExceptionMessageFromScriptError(pythonProcessOutput));
        }

        logger.info(String.format("%s complete.", getClass().getSimpleName()));

        return null;
    }

    private void validateArguments() {
        germlineCallingArgumentCollection.validate();
        germlineDenoisingModelArgumentCollection.validate();
        germlineCNVHybridADVIArgumentCollection.validate();

        Utils.validateArg(inputReadCountPaths.size() == new HashSet<>(inputReadCountPaths).size(),
                "List of input read-count file paths cannot contain duplicates.");

        inputReadCountPaths.forEach(CopyNumberArgumentValidationUtils::validateInputs);
        CopyNumberArgumentValidationUtils.validateInputs(
                inputContigPloidyCallsDir,
                inputModelDir,
                inputAnnotatedIntervalsFile);
        Utils.nonEmpty(outputPrefix);
        CopyNumberArgumentValidationUtils.validateAndPrepareOutputDirectories(outputDir);
    }

    private void resolveIntervals() {
        if (inputModelDir != null) {
            //intervals are retrieved from the input model directory
            specifiedIntervalsFile = new File(inputModelDir, INPUT_MODEL_INTERVAL_FILE);
            CopyNumberArgumentValidationUtils.validateInputs(specifiedIntervalsFile);
            specifiedIntervals = new SimpleIntervalCollection(specifiedIntervalsFile);
        } else {
            //get sequence dictionary and intervals from the first read-count file to use to validate remaining files
            //(this first file is read again below, which is slightly inefficient but is probably not worth the extra code)
            final String firstReadCountPath = inputReadCountPaths.get(0);
            specifiedIntervals = CopyNumberArgumentValidationUtils.resolveIntervals(
                    firstReadCountPath, intervalArgumentCollection, logger);

            //in cohort mode, intervals are specified via -L; we write them to a temporary file
            specifiedIntervalsFile = IOUtils.createTempFile("intervals", ".tsv");
            //get GC content (null if not provided)
            final AnnotatedIntervalCollection subsetAnnotatedIntervals =
                    CopyNumberArgumentValidationUtils.validateAnnotatedIntervalsSubset(
                            inputAnnotatedIntervalsFile, specifiedIntervals, logger);
            if (subsetAnnotatedIntervals != null) {
                logger.info("GC-content annotations for intervals found; explicit GC-bias correction will be performed...");
                subsetAnnotatedIntervals.write(specifiedIntervalsFile);
            } else {
                logger.info("No GC-content annotations for intervals found; explicit GC-bias correction will not be performed...");
                specifiedIntervals.write(specifiedIntervalsFile);
            }
        }

        if (runMode == RunMode.COHORT) {
            logger.info("Running the tool in COHORT mode...");
            Utils.validateArg(inputReadCountPaths.size() > 1, "At least two samples must be provided in " +
                    "COHORT mode.");
            if (inputModelDir != null) {
                logger.info("(advanced feature) A denoising-model directory is provided in COHORT mode; " +
                        "using the model for initialization and ignoring specified and/or annotated intervals.");
            }
        } else { // case run-mode
            logger.info("Running the tool in CASE mode...");
            Utils.validateArg(inputModelDir != null, "An input denoising-model directory must be provided in " +
                    "CASE mode.");
            if (intervalArgumentCollection.intervalsSpecified()) {
                throw new UserException.BadInput("Invalid combination of inputs: Running in CASE mode, " +
                        "but intervals were provided.");
            }
            if (inputAnnotatedIntervalsFile != null) {
                throw new UserException.BadInput("Invalid combination of inputs: Running in CASE mode," +
                        "but annotated intervals were provided.");
            }
        }
    }

    private List<File> writeIntervalSubsetReadCountFiles() {
        logger.info("Validating and aggregating data from input read-count files...");
        final List<File> intervalSubsetReadCountFiles = new ArrayList<>(inputReadCountPaths.size());
        streamOfSubsettedAndValidatedReadCounts(inputReadCountPaths, specifiedIntervals, logger)
                .forEach(subsetReadCounts -> {
                    final File intervalSubsetReadCountFile = IOUtils.createTempFile(
                            subsetReadCounts.getMetadata().getSampleName() + ".rc", ".tsv"); // we add ".rc" to ensure prefix will be >= 3 characters, see https://github.com/broadinstitute/gatk/issues/7410
                    subsetReadCounts.write(intervalSubsetReadCountFile);
                    intervalSubsetReadCountFiles.add(intervalSubsetReadCountFile);
                });
        return intervalSubsetReadCountFiles;
    }

    private List<String> composePythonArguments(final List<File> intervalSubsetReadCountFiles, final int randomSeed) {
        final String outputDirArg = CopyNumberArgumentValidationUtils.addTrailingSlashIfNecessary(outputDir.getAbsolutePath());

        //add required arguments
        final List<String> arguments = new ArrayList<>(Arrays.asList(
                "--ploidy_calls_path=" + CopyNumberArgumentValidationUtils.getCanonicalPath(inputContigPloidyCallsDir),
                "--output_calls_path=" + CopyNumberArgumentValidationUtils.getCanonicalPath(outputDirArg + outputPrefix + CALLS_PATH_SUFFIX),
                "--output_tracking_path=" + CopyNumberArgumentValidationUtils.getCanonicalPath(outputDirArg + outputPrefix + TRACKING_PATH_SUFFIX)));

        //if a model path is given, add it to the argument (both COHORT and CASE modes)
        if (inputModelDir != null) {
            arguments.add("--input_model_path=" + CopyNumberArgumentValidationUtils.getCanonicalPath(inputModelDir));
        }
        arguments.add(String.format("--random_seed=%d", randomSeed));

        // in CASE mode, explicit GC bias modeling is set by the model
        if (runMode == RunMode.COHORT) {
            //these are the annotated intervals, if provided
            arguments.add("--modeling_interval_list=" + CopyNumberArgumentValidationUtils.getCanonicalPath(specifiedIntervalsFile));
            arguments.add("--output_model_path=" + CopyNumberArgumentValidationUtils.getCanonicalPath(outputDirArg + outputPrefix + MODEL_PATH_SUFFIX));
            if (inputAnnotatedIntervalsFile != null) {
                arguments.add("--enable_explicit_gc_bias_modeling=True");
            } else {
                arguments.add("--enable_explicit_gc_bias_modeling=False");
            }
        }

        arguments.add("--read_count_tsv_files");
        arguments.addAll(intervalSubsetReadCountFiles.stream().map(CopyNumberArgumentValidationUtils::getCanonicalPath).collect(Collectors.toList()));

        arguments.addAll(germlineDenoisingModelArgumentCollection.generatePythonArguments(runMode));
        arguments.addAll(germlineCallingArgumentCollection.generatePythonArguments(runMode));
        arguments.addAll(germlineCNVHybridADVIArgumentCollection.generatePythonArguments());

        return arguments;
    }
}
