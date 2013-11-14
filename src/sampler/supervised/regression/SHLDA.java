package sampler.supervised.regression;

import core.AbstractSampler;
import core.crossvalidation.Fold;
import data.ResponseTextDataset;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Stack;
import optimization.GurobiMLRL1Norm;
import optimization.GurobiMLRL2Norm;
import org.apache.commons.cli.BasicParser;
import org.apache.commons.cli.Options;
import sampler.TwoLevelHierSegLDA;
import sampler.supervised.Regressor;
import sampling.likelihood.DirMult;
import sampling.likelihood.TruncatedStickBreaking;
import sampling.util.FullTable;
import sampling.util.Restaurant;
import sampling.util.SparseCount;
import sampling.util.TopicTreeNode;
import sampling.util.TopicTreeNode.PathAssumption;
import util.CLIUtils;
import util.IOUtils;
import util.MiscUtils;
import util.RankingItem;
import util.SamplerUtils;
import util.SparseVector;
import util.StatisticsUtils;
import util.evaluation.Measurement;
import util.evaluation.RegressionEvaluation;

/**
 *
 * @author vietan
 */
public class SHLDA extends AbstractSampler implements Regressor<ResponseTextDataset> {

    public static final Double WEIGHT_THRESHOLD = 10e-2;
    public static final int PSEUDO_TABLE_INDEX = -1;
    public static final int PSEUDO_NODE_INDEX = -1;
    // hyperparameter indices
    public static final int ALPHA = 0;
    public static final int RHO = 1;
    public static final int GEM_MEAN = 2;
    public static final int GEM_SCALE = 3;
    public static final int TAU_MEAN = 4;
    public static final int TAU_SCALE = 5;
    // hyperparameters
    protected double[] betas;  // topics concentration parameter
    protected double[] gammas; // DP
    protected double[] mus;    // regression parameter means
    protected double[] sigmas; // regression parameter variances
    // input data
    protected int[][][] words;  // [D] x [S_d] x [N_ds]: words
    protected double[] responses; // [D]
    protected int L; // level of hierarchies
    protected int V; // vocabulary size
    protected int D; // number of documents
    protected double T; // regularizer's parameter
    // input statistics
    private int sentCount;
    private int tokenCount;
    private int[] docTokenCounts;
    private double logAlpha;
    private double sqrtRho;
    private double[] sqrtSigmas;
    private double[] logGammas;
    private PathAssumption pathAssumption;
    // latent variables
    private STable[][] c; // path assigned to sentences
    private int[][][] z; // level assigned to tokens
    // state structure
    private SNode globalTreeRoot; // tree
    private Restaurant<STable, Integer, SNode>[] localRestaurants; // franchise
    // state statistics stored
    private SparseVector lexicalWeights;
    private ArrayList<Integer> lexicalList;
    private int[][][] sentLevelCounts;
    private double[] docLexicalWeights;
    private double[] docTopicWeights;
    private double[][] docLexicalDesignMatrix;
    // over time
    private ArrayList<double[]> lexicalWeightsOverTime;
    // auxiliary
    private double[] uniform;
    private TruncatedStickBreaking emptyStick;
    private int numTokenAsgnsChange;
    private int numSentAsntsChange;
    private int numTableAsgnsChange;
    // for initialization
    private int numFirstTopics = 15; // # first-level topics
    private int numSecondTopics = 3; // # second-level topics per first-level topic

    public void configure(String folder,
            int V, int L,
            double T,
            double alpha,
            double rho,
            double gem_mean,
            double gem_scale,
            double tau_mean,
            double tau_scale,
            double[] betas,
            double[] gammas,
            double[] mus,
            double[] sigmas,
            InitialState initState,
            PathAssumption pathAssumption,
            boolean paramOpt,
            int burnin, int maxiter, int samplelag, int repInt) {
        if (verbose) {
            logln("Configuring ...");
        }
        this.folder = folder;

        this.V = V;
        this.L = L;
        this.T = T;

        this.betas = betas;
        this.gammas = gammas;
        this.mus = mus;
        this.sigmas = sigmas;

        this.hyperparams = new ArrayList<Double>();
        this.hyperparams.add(alpha);
        this.hyperparams.add(rho);
        this.hyperparams.add(gem_mean);
        this.hyperparams.add(gem_scale);
        this.hyperparams.add(tau_mean);
        this.hyperparams.add(tau_scale);
        for (double beta : betas) {
            this.hyperparams.add(beta);
        }
        for (double gamma : gammas) {
            this.hyperparams.add(gamma);
        }
        for (double mu : mus) {
            this.hyperparams.add(mu);
        }
        for (double sigma : sigmas) {
            this.hyperparams.add(sigma);
        }

        this.updatePrecomputedHyperparameters();

        this.sampledParams = new ArrayList<ArrayList<Double>>();
        this.sampledParams.add(cloneHyperparameters());

        this.BURN_IN = burnin;
        this.MAX_ITER = maxiter;
        this.LAG = samplelag;
        this.REP_INTERVAL = repInt;

        this.pathAssumption = pathAssumption;
        this.initState = initState;
        this.paramOptimized = paramOpt;
        this.prefix += initState.toString();

        this.setName();

        // assert dimensions
        if (this.betas.length != this.L) {
            throw new RuntimeException("Vector betas must have length " + this.L
                    + ". Current length = " + this.betas.length);
        }
        if (this.gammas.length != this.L - 1) {
            throw new RuntimeException("Vector gammas must have length " + (this.L - 1)
                    + ". Current length = " + this.gammas.length);
        }
        if (this.mus.length != this.L) {
            throw new RuntimeException("Vector mus must have length " + this.L
                    + ". Current length = " + this.mus.length);
        }
        if (this.sigmas.length != this.L) {
            throw new RuntimeException("Vector sigmas must have length " + this.L
                    + ". Current length = " + this.sigmas.length);
        }

        this.uniform = new double[V];
        for (int v = 0; v < V; v++) {
            this.uniform[v] = 1.0 / V;
        }

        if (!debug) {
            System.err.close();
        }

        if (verbose) {
            logln("--- V = " + V);
            logln("--- T = " + T);
            logln("--- # documents = " + D); // number of groups
            logln("--- # sentences = " + sentCount);
            logln("--- # tokens = " + tokenCount);

            logln("--- folder\t" + folder);
            logln("--- max level:\t" + L);
            logln("--- alpha:\t" + hyperparams.get(ALPHA));
            logln("--- rho:\t" + hyperparams.get(RHO));
            logln("--- GEM mean:\t" + hyperparams.get(GEM_MEAN));
            logln("--- GEM scale:\t" + hyperparams.get(GEM_SCALE));
            logln("--- tau mean:\t" + hyperparams.get(TAU_MEAN));
            logln("--- tau scale:\t" + hyperparams.get(TAU_SCALE));

            logln("--- betas:\t" + MiscUtils.arrayToString(betas));
            logln("--- gammas:\t" + MiscUtils.arrayToString(gammas));
            logln("--- reg mus:\t" + MiscUtils.arrayToString(mus));
            logln("--- reg sigmas:\t" + MiscUtils.arrayToString(sigmas));

            logln("--- burn-in:\t" + BURN_IN);
            logln("--- max iter:\t" + MAX_ITER);
            logln("--- sample lag:\t" + LAG);
            logln("--- paramopt:\t" + paramOptimized);
            logln("--- initialize:\t" + this.initState);
            logln("--- path assumption:\t" + this.pathAssumption);

            logln("--- responses:");
            logln("--- --- mean\t" + MiscUtils.formatDouble(StatisticsUtils.mean(responses)));
            logln("--- --- stdv\t" + MiscUtils.formatDouble(StatisticsUtils.standardDeviation(responses)));
            int[] histogram = StatisticsUtils.bin(responses, 10);
            for (int ii = 0; ii < histogram.length; ii++) {
                logln("--- --- " + ii + "\t" + histogram[ii]);
            }
        }
    }

    public void setNumInitTopics(int numTopics) {
        this.numFirstTopics = numTopics;
    }

    public void setNumInitFrames(int numFrames) {
        this.numSecondTopics = numFrames;
    }

    private void updatePrecomputedHyperparameters() {
        logAlpha = Math.log(hyperparams.get(ALPHA));
        sqrtRho = Math.sqrt(hyperparams.get(RHO));
        sqrtSigmas = new double[sigmas.length];
        for (int i = 0; i < sqrtSigmas.length; i++) {
            sqrtSigmas[i] = Math.sqrt(sigmas[i]);
        }
        logGammas = new double[gammas.length];
        for (int i = 0; i < logGammas.length; i++) {
            logGammas[i] = Math.log(gammas[i]);
        }
    }

    private void updateDocumentTopicWeights() {
        this.docTopicWeights = new double[D];
        for (int d = 0; d < D; d++) {
            for (int s = 0; s < words[d].length; s++) {
                this.docTopicWeights[d] += computeTopicWeight(d, s);
            }
        }
    }

    private void updateDocumentLexicalWeights() {
        this.docLexicalWeights = new double[D];
        for (int d = 0; d < D; d++) {
            for (int s = 0; s < words[d].length; s++) {
                for (int n = 0; n < words[d][s].length; n++) {
                    Double w = this.lexicalWeights.get(words[d][s][n]);
                    if (w != null) {
                        this.docLexicalWeights[d] += w;
                    }
                }
            }
        }
    }

    @Override
    public String getName() {
        return this.name;
    }

    protected void setName() {
        StringBuilder str = new StringBuilder();
        str.append(this.prefix)
                .append("_SHLDA")
                .append("_B-").append(BURN_IN)
                .append("_M-").append(MAX_ITER)
                .append("_L-").append(LAG)
                .append("_T-").append(T)
                .append("_a-").append(formatter.format(hyperparams.get(ALPHA)))
                .append("_r-").append(formatter.format(hyperparams.get(RHO)))
                .append("_gm-").append(formatter.format(hyperparams.get(GEM_MEAN)))
                .append("_gs-").append(formatter.format(hyperparams.get(GEM_SCALE)))
                .append("_tm-").append(formatter.format(hyperparams.get(TAU_MEAN)))
                .append("_ts-").append(formatter.format(hyperparams.get(TAU_SCALE)));
        int count = TAU_SCALE + 1;
        str.append("_b");
        for (int i = 0; i < betas.length; i++) {
            str.append("-").append(formatter.format(hyperparams.get(count++)));
        }
        str.append("_g");
        for (int i = 0; i < gammas.length; i++) {
            str.append("-").append(formatter.format(hyperparams.get(count++)));
        }
        str.append("_m");
        for (int i = 0; i < mus.length; i++) {
            str.append("-").append(formatter.format(mus[i]));
        }
        str.append("_s");
        for (int i = 0; i < sigmas.length; i++) {
            str.append("-").append(formatter.format(sigmas[i]));
        }
        str.append("_opt-").append(this.paramOptimized);
        str.append("_").append(this.paramOptimized);
        this.name = str.toString();
    }

    private void computeDataStatistics() {
        sentCount = 0;
        tokenCount = 0;
        docTokenCounts = new int[D];
        for (int d = 0; d < D; d++) {
            sentCount += words[d].length;
            for (int s = 0; s < words[d].length; s++) {
                tokenCount += words[d][s].length;
                docTokenCounts[d] += words[d][s].length;
            }
        }
    }

    public void train(int[][][] ws, double[] rs) {
        this.words = ws;
        this.responses = rs;
        this.D = this.words.length;
        this.computeDataStatistics();
    }

    @Override
    public void train(ResponseTextDataset trainData) {
        this.words = trainData.getSentenceWords();
        this.responses = trainData.getResponses();
        this.D = this.words.length;
        this.computeDataStatistics();
    }

    public void test(int[][][] ws) {
        testSampler(ws);
    }

    @Override
    public void test(ResponseTextDataset testData) {
        testSampler(testData.getSentenceWords());
    }

    @Override
    public void initialize() {
        if (verbose) {
            logln("Initializing ...");
        }

        iter = INIT;

        initializeLexicalWeights();
        initializeModelStructure();
        initializeDataStructure();
        initializeAssignments();

        updateDocumentTopicWeights();
        updateDocumentLexicalWeights();

        if (verbose) {
            logln("--- --- Done initializing.\n" + getCurrentState());
            logln(printGlobalTree());
            logln(printGlobalTreeSummary());
            logln(printLocalRestaurantSummary());
        }

        if (debug) {
            validate("Initialized");
        }
    }

    /**
     * Initialize lexical weights using LASSO
     */
    private void initializeLexicalWeights() {
        if (verbose) {
            logln("Initializing lexical weights ...");
        }

        this.lexicalWeights = new SparseVector();
        this.lexicalList = new ArrayList<Integer>();
        if (T > 0) {
            GurobiMLRL1Norm lasso = new GurobiMLRL1Norm(T);
            double[] ws = null;
            try {
                File regFile = new File(this.folder, "init-weights-" + T + ".txt");
                if (regFile.exists()) {
                    ws = inputWeights(regFile);
                } else {
                    if (verbose) {
                        logln("--- Initial weights not found. " + regFile);
                        logln("--- Optimizing ...");
                    }
                    double[][] designMatrix = new double[D][V];
                    for (int d = 0; d < D; d++) {
                        for (int s = 0; s < words[d].length; s++) {
                            for (int n = 0; n < words[d][s].length; n++) {
                                designMatrix[d][words[d][s][n]]++;
                            }
                        }
                        for (int v = 0; v < V; v++) {
                            designMatrix[d][v] /= docTokenCounts[d];
                        }
                    }
                    lasso.setDesignMatrix(designMatrix);
                    lasso.setResponseVector(responses);
                    ws = lasso.solve();
                    outputWeights(regFile, ws);
                }
            } catch (Exception e) {
                e.printStackTrace();
                throw new RuntimeException("Exception while initializing lexical weights");
            }

            int count = 0;
            for (int v = 0; v < V; v++) {
                if (Math.abs(ws[v]) >= WEIGHT_THRESHOLD) {
                    this.lexicalWeights.set(v, ws[v]);
                    this.lexicalList.add(v);
                    count++;
                }
            }
            if (verbose) {
                logln("--- # non-zero lexical weights: " + count);
            }

            // document design matrix for lexical items
            this.docLexicalDesignMatrix = new double[D][count];
            for (int d = 0; d < D; d++) {
                for (int s = 0; s < words[d].length; s++) {
                    for (int n = 0; n < words[d][s].length; n++) {
                        int w = words[d][s][n];
                        if (this.lexicalWeights.containsIndex(w)) {
                            docLexicalDesignMatrix[d][lexicalList.indexOf(w)]++;
                        }
                    }
                }
                for (int ii = 0; ii < count; ii++) {
                    docLexicalDesignMatrix[d][ii] /= docTokenCounts[d];
                }
            }
        }
    }

    /**
     * Initialize model structure.
     */
    private void initializeModelStructure() {
        DirMult dmModel = new DirMult(V, betas[0] * V, uniform);
        double regParam = 0.0;
        this.globalTreeRoot = new SNode(iter, 0, 0, dmModel, regParam, null);

        this.emptyStick = new TruncatedStickBreaking(L, hyperparams.get(GEM_MEAN),
                hyperparams.get(GEM_SCALE));
    }

    /**
     * Initialize data-specific structures.
     */
    private void initializeDataStructure() {
        this.localRestaurants = new Restaurant[D];
        for (int d = 0; d < D; d++) {
            this.localRestaurants[d] = new Restaurant<STable, Integer, SNode>();
        }

        this.sentLevelCounts = new int[D][][];
        for (int d = 0; d < D; d++) {
            this.sentLevelCounts[d] = new int[words[d].length][L];
        }

        this.c = new STable[D][];
        this.z = new int[D][][];
        for (int d = 0; d < D; d++) {
            c[d] = new STable[words[d].length];
            z[d] = new int[words[d].length][];
            for (int s = 0; s < words[d].length; s++) {
                z[d][s] = new int[words[d][s].length];
            }
        }
    }

    /**
     * Initialize assignments.
     */
    private void initializeAssignments() {
        switch (initState) {
            case RANDOM:
                this.initializeRandomAssignments();
                break;
            case PRESET:
                this.initializerHierSegLDAAssignments();
                break;
            default:
                throw new RuntimeException("Initialization not supported");
        }
    }

    private void initializerHierSegLDAAssignments() {
        if (verbose) {
            logln("--- Initializing assignments using hierarchical segmented LDA ...");
        }
        double[] empBackgroundTopic = new double[V];
        int[][] docWords = new int[D][];
        for (int d = 0; d < D; d++) {
            docWords[d] = new int[docTokenCounts[d]];
            int count = 0;
            for (int s = 0; s < words[d].length; s++) {
                for (int n = 0; n < words[d][s].length; n++) {
                    docWords[d][count++] = words[d][s][n];
                    empBackgroundTopic[words[d][s][n]]++;
                }
            }
        }

        for (int v = 0; v < V; v++) {
            empBackgroundTopic[v] /= tokenCount;
        }

        int init_burnin = 100;
        int init_maxiter = 200;
        int init_samplelag = 50;

        double alpha_1 = 0.1;
        double alpha_2 = 0.01;
        double beta_1 = 0.1;
        double beta_2 = 0.01;

        TwoLevelHierSegLDA sampler = new TwoLevelHierSegLDA();
        sampler.setVerbose(verbose);
        sampler.setDebug(debug);
        sampler.setLog(false);
        sampler.setReport(false);

        sampler.configure(
                null,
                docWords, V, numFirstTopics, numSecondTopics,
                alpha_1, alpha_2,
                beta_1, beta_2,
                initState,
                paramOptimized, init_burnin, init_maxiter, init_samplelag, 1);

        try {
            File initFile = new File(this.folder, "hslda-init-"
                    + numFirstTopics + "-"
                    + numSecondTopics + ".txt");
            if (initFile.exists()) {
                sampler.inputState(initFile);
            } else {
                sampler.initialize();
                sampler.iterate();
                sampler.outputState(initFile);
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
        setLog(true);

        // initialize structure
        int topicLevel = 1;
        int frameLevel = 2;
        ArrayList<SNode> frameNodes = new ArrayList<SNode>();
        this.globalTreeRoot.setTopic(empBackgroundTopic);
        for (int k = 0; k < numFirstTopics; k++) {
            SNode topicNode = new SNode(iter, k, 1,
                    new DirMult(V, betas[topicLevel] * V, 1.0 / V),
                    SamplerUtils.getGaussian(mus[topicLevel], sigmas[topicLevel]),
                    globalTreeRoot);
            topicNode.setTopic(sampler.getFirstLevelTopics()[k].getDistribution());
            globalTreeRoot.addChild(k, topicNode);

            for (int f = 0; f < numSecondTopics; f++) {
                SNode frameNode = new SNode(iter, f, frameLevel,
                        new DirMult(V, betas[frameLevel] * V, 1.0 / V),
                        SamplerUtils.getGaussian(mus[topicLevel], sigmas[topicLevel]), topicNode);
                frameNode.setTopic(sampler.getSecondLevelTopics()[k][f].getDistribution());
                topicNode.addChild(f, frameNode);
                frameNodes.add(frameNode);
            }
        }

        logln(printGlobalTree());

        // assign
        for (int d = 0; d < D; d++) {
            for (int s = 0; s < words[d].length; s++) {
                // create a new table for each sentence
                TruncatedStickBreaking stick = new TruncatedStickBreaking(L,
                        hyperparams.get(GEM_MEAN), hyperparams.get(GEM_SCALE));
                STable table = new STable(iter, s, null, d, stick);
                localRestaurants[d].addTable(table);
                localRestaurants[d].addCustomerToTable(s, table.getIndex());
                c[d][s] = table;

                // assume all tokens are at the leave node, choose a path
                SparseCount sentObs = new SparseCount();
                for (int n = 0; n < words[d][s].length; n++) {
                    sentObs.increment(words[d][s][n]);
                }
                ArrayList<Double> logprobs = new ArrayList<Double>();
                for (int ii = 0; ii < frameNodes.size(); ii++) {
                    double lp = frameNodes.get(ii).getLogProbability(sentObs);
                    logprobs.add(lp);
                }
                int idx = SamplerUtils.logMaxRescaleSample(logprobs);
                SNode frameNode = frameNodes.get(idx);
                table.setContent(frameNode);
                addTableToPath(frameNode);

                // push all tokens to the leaf node
                for (int n = 0; n < words[d][s].length; n++) {
                    SNode[] path = getPathFromNode(frameNode);
                    logprobs = new ArrayList<Double>();
                    for (int l = 0; l < L; l++) {
                        double lp = path[l].getLogProbability(words[d][s][n]);
                        logprobs.add(lp);
                    }
                    idx = SamplerUtils.logMaxRescaleSample(logprobs);

                    z[d][s][n] = idx;
                    table.incrementLevelCount(z[d][s][n]);
                    sentLevelCounts[d][s][z[d][s][n]]++;
                    path[z[d][s][n]].getContent().increment(words[d][s][n]);
                }
            }
        }

        if (debug) {
            validate("After initial assignments");
        }

        this.sampleTopics();

        if (verbose) {
            logln("--- --- Start sampling paths for tables\n" + getCurrentState());
        }
        for (int d = 0; d < D; d++) {
            for (STable table : localRestaurants[d].getTables()) {
                samplePathForTable(d, table, REMOVE, ADD, !OBSERVED, EXTEND);
            }
        }
    }

    private void initializeRandomAssignments() {
        if (verbose) {
            logln("--- Initializing random assignments ...");
        }

        for (int d = 0; d < D; d++) {
            for (int s = 0; s < words[d].length; s++) {
                // create a new table for each sentence
                TruncatedStickBreaking stick = new TruncatedStickBreaking(L,
                        hyperparams.get(GEM_MEAN), hyperparams.get(GEM_SCALE));
                STable table = new STable(iter, s, null, d, stick);
                localRestaurants[d].addTable(table);
                localRestaurants[d].addCustomerToTable(s, table.getIndex());
                c[d][s] = table;

                // create a new path for each table
                SNode node = globalTreeRoot;
                for (int l = 0; l < L - 1; l++) {
                    node = createNode(node);
                }
                addTableToPath(node);
                table.setContent(node);

                // sample level
                for (int n = 0; n < words[d][s].length; n++) {
                    sampleLevelForToken(d, s, n, !REMOVE, ADD, !OBSERVED);
                }

                if (d > 0 || s > 0) {
                    sampleTableForSentence(d, s, REMOVE, ADD, !OBSERVED, EXTEND);
                }

                for (int n = 0; n < words[d][s].length; n++) {
                    sampleLevelForToken(d, s, n, REMOVE, ADD, !OBSERVED);
                }
            }
        }

        if (verbose) {
            logln("--- --- Start sampling paths for tables\n" + getCurrentState());
        }
        for (int d = 0; d < D; d++) {
            for (STable table : localRestaurants[d].getTables()) {
                samplePathForTable(d, table, REMOVE, ADD, !OBSERVED, EXTEND);
            }
        }
    }

    @Override
    public void iterate() {
        if (verbose) {
            logln("Iterating ...");
        }
        this.logLikelihoods = new ArrayList<Double>();
        this.lexicalWeightsOverTime = new ArrayList<double[]>();

        File repFolderPath = new File(getSamplerFolderPath(), ReportFolder);
        try {
            if (report && !repFolderPath.exists()) {
                IOUtils.createFolder(repFolderPath);
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }

        if (log && !isLogging()) {
            openLogger();
        }

        logln(getClass().toString());
        startTime = System.currentTimeMillis();

        for (iter = 0; iter < MAX_ITER; iter++) {
            double loglikelihood = this.getLogLikelihood();
            logLikelihoods.add(loglikelihood);

            double[] storeWeights = new double[V];
            for (int v = 0; v < V; v++) {
                Double w = this.lexicalWeights.get(v);
                if (w != null) {
                    storeWeights[v] = w;
                }
            }
            this.lexicalWeightsOverTime.add(storeWeights);

            if (verbose) {
                String str = "Iter " + iter
                        + "\t llh = " + MiscUtils.formatDouble(loglikelihood)
                        + "\t # tokens change: " + numTokenAsgnsChange
                        + "\t # sents change: " + numSentAsntsChange
                        + "\t # tables change: " + numTableAsgnsChange
                        + "\n" + getCurrentState()
                        + "\n";
                if (iter < BURN_IN) {
                    logln("--- Burning in. " + str);
                } else {
                    logln("--- Sampling. " + str);
                }
            }

            numTableAsgnsChange = 0;
            numSentAsntsChange = 0;
            numTokenAsgnsChange = 0;

            for (int d = 0; d < D; d++) {
                for (int s = 0; s < words[d].length; s++) {
                    sampleTableForSentence(d, s, REMOVE, ADD, OBSERVED, EXTEND);

                    for (int n = 0; n < words[d][s].length; n++) {
                        sampleLevelForToken(d, s, n, REMOVE, ADD, OBSERVED);
                    }
                }

                for (STable table : this.localRestaurants[d].getTables()) {
                    samplePathForTable(d, table, REMOVE, ADD, OBSERVED, EXTEND);
                }
            }

            optimizeTopicRegressionParameters();

            if (this.T > 0) {
                optimizeLexicalRegressionParameters();
            }

            sampleTopics();

            if (verbose) {
                double[] trPredResponses = getRegressionValues();
                RegressionEvaluation eval = new RegressionEvaluation(
                        (responses),
                        (trPredResponses));
                eval.computeCorrelationCoefficient();
                eval.computeMeanSquareError();
                eval.computeRSquared();
                ArrayList<Measurement> measurements = eval.getMeasurements();
                for (Measurement measurement : measurements) {
                    logln("--- --- " + measurement.getName() + ":\t" + measurement.getValue());
                }
            }

            if (iter >= BURN_IN && iter % LAG == 0) {
                if (paramOptimized) {
                    if (verbose) {
                        logln("--- --- Slice sampling ...");
                    }

                    sliceSample();
                    this.sampledParams.add(this.cloneHyperparameters());

                    if (verbose) {
                        logln("--- ---- " + MiscUtils.listToString(hyperparams));
                    }
                }
            }

            if (debug) {
                this.validate("Iteration " + iter);
            }

            System.out.println();

            // store model
            if (report && iter >= BURN_IN && iter % LAG == 0) {
                outputState(new File(repFolderPath, "iter-" + iter + ".zip"));
                outputTopicTopWords(new File(repFolderPath,
                        "iter-" + iter + "-top-words.txt"), 15);
            }
        }

        // output final model
        if (report) {
            outputState(new File(repFolderPath, "iter-" + iter + ".zip"));
        }

        if (verbose) {
            logln(printGlobalTreeSummary());
            logln(printLocalRestaurantSummary());
        }

        float ellapsedSeconds = (System.currentTimeMillis() - startTime) / (1000);
        logln("Total runtime iterating: " + ellapsedSeconds + " seconds");

        if (log && isLogging()) {
            closeLogger();
        }

        try {
            if (paramOptimized && log) {
                this.outputSampledHyperparameters(new File(getSamplerFolderPath(),
                        "hyperparameters.txt"));
            }

            if (report) {
                // weights over time
                BufferedWriter writer = IOUtils.getBufferedWriter(
                        new File(getSamplerFolderPath(), "weights-over-time.txt"));
                for (int v = 0; v < V; v++) {
                    writer.write(v + "\t" + wordVocab.get(v));
                    for (int i = 0; i < this.lexicalWeightsOverTime.size(); i++) {
                        writer.write("\t" + this.lexicalWeightsOverTime.get(i)[v]);
                    }
                    writer.write("\n");
                }
                writer.close();

                // average weights
                writer = IOUtils.getBufferedWriter(new File(getSamplerFolderPath(), "weights.txt"));
                for (int v = 0; v < V; v++) {
                    ArrayList<Double> ws = new ArrayList<Double>();
                    for (int ii = 0; ii < lexicalWeightsOverTime.size(); ii++) {
                        ws.add(lexicalWeightsOverTime.get(ii)[v]);
                    }
                    writer.write(v
                            + "\t" + wordVocab.get(v)
                            + "\t" + StatisticsUtils.mean(ws)
                            + "\t" + StatisticsUtils.standardDeviation(ws)
                            + "\n");
                }

                writer.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    public double[] getRegressionValues() {
        double[] regValues = new double[D];
        for (int d = 0; d < D; d++) {
            double sum = docTopicWeights[d] + docLexicalWeights[d];
            regValues[d] = sum / docTokenCounts[d];
        }
        return regValues;
    }

    /**
     * Add a customer to a path. A path is specified by the pointer to its leaf
     * node. If the given node is not a leaf node, an exception will be thrown.
     * The number of customers at each node on the path will be incremented.
     *
     * @param leafNode The leaf node of the path
     */
    private void addTableToPath(SNode leafNode) {
        SNode node = leafNode;
        while (node != null) {
            node.incrementNumCustomers();
            node = node.getParent();
        }
    }

    /**
     * Remove a customer from a path. A path is specified by the pointer to its
     * leaf node. The number of customers at each node on the path will be
     * decremented. If the number of customers at a node is 0, the node will be
     * removed.
     *
     * @param leafNode The leaf node of the path
     * @return Return the node that specifies the path that the leaf node is
     * removed from. If a lower-level node has no customer, it will be removed
     * and the lowest parent node on the path that has non-zero number of
     * customers will be returned.
     */
    private SNode removeTableFromPath(SNode leafNode) {
        SNode retNode = leafNode;
        SNode node = leafNode;
        while (node != null) {
            node.decrementNumCustomers();
            if (node.isEmpty()) {
                retNode = node.getParent();
                node.getParent().removeChild(node.getIndex());
            }
            node = node.getParent();
        }
        return retNode;
    }

    /**
     * Add a set of observations (given their level assignments) to a path
     *
     * @param leafNode The leaf node identifying the path
     * @param observations The observations per level
     */
    private SNode[] addObservationsToPath(SNode leafNode, SparseCount[] observations) {
        SNode[] path = getPathFromNode(leafNode);
        for (int l = 0; l < L; l++) {
            addObservationsToNode(path[l], observations[l]);
        }
        return path;
    }

    /**
     * Remove a set of observations (given their level assignments) from a path
     *
     * @param leafNode The leaf node identifying the path
     * @param observations The observations per level
     */
    private SNode[] removeObservationsFromPath(SNode leafNode, SparseCount[] observations) {
        SNode[] path = getPathFromNode(leafNode);
        for (int l = 0; l < L; l++) {
            removeObservationsFromNode(path[l], observations[l]);
        }
        return path;
    }

    /**
     * Remove a set of observations from a node
     *
     * @param node The node
     * @param observations The set of observations
     */
    private void removeObservationsFromNode(SNode node, SparseCount observations) {
        for (int obs : observations.getIndices()) {
            int count = observations.getCount(obs);
            node.getContent().changeCount(obs, -count);
        }
    }

    /**
     * Add a set of observations to a node
     *
     * @param node The node
     * @param observations The set of observations
     */
    private void addObservationsToNode(SNode node, SparseCount observations) {
        for (int obs : observations.getIndices()) {
            int count = observations.getCount(obs);
            node.getContent().changeCount(obs, count);
        }
    }

    private SNode createNewPath(SNode internalNode) {
        SNode node = internalNode;
        for (int l = internalNode.getLevel(); l < L - 1; l++) {
            node = this.createNode(node);
        }
        return node;
    }

    /**
     * Create a node given a parent node
     *
     * @param parent The parent node
     */
    private SNode createNode(SNode parent) {
        int nextChildIndex = parent.getNextChildIndex();
        int level = parent.getLevel() + 1;
        DirMult dmm = new DirMult(V, betas[level] * V, uniform);
        double regParam = SamplerUtils.getGaussian(mus[level], sigmas[level]);
        SNode child = new SNode(iter, nextChildIndex, level, dmm, regParam, parent);
        return parent.addChild(nextChildIndex, child);
    }

    /**
     * Get the observation counts per level of a sentence given the current
     * token assignments.
     *
     * @param d The document index
     * @param s The sentence index
     */
    private SparseCount[] getSentObsCountPerLevel(int d, int s) {
        SparseCount[] counts = new SparseCount[L];
        for (int ll = 0; ll < L; ll++) {
            counts[ll] = new SparseCount();
        }
        for (int n = 0; n < words[d][s].length; n++) {
            int type = words[d][s][n];
            int level = z[d][s][n];
            counts[level].increment(type);
        }
        return counts;
    }

    /**
     * Get the observation counts per level of a given table
     *
     * @param d The document index
     * @param table The table
     */
    private SparseCount[] getTableObsCountPerLevel(int d, STable table) {
        // observations of sentences currently being assign to this table
        SparseCount[] obsCountPerLevel = new SparseCount[L];
        for (int l = 0; l < L; l++) {
            obsCountPerLevel[l] = new SparseCount();
        }

        for (int s : table.getCustomers()) {
            for (int n = 0; n < words[d][s].length; n++) {
                int level = z[d][s][n];
                int obs = words[d][s][n];
                obsCountPerLevel[level].increment(obs);
            }
        }
        return obsCountPerLevel;
    }

    /**
     * Sample a level for a token
     *
     * @param d The document index
     * @param s The sentence index
     * @param n The token index
     * @param remove Whether the current assignment should be removed
     * @param add Whether the new assignment should be added
     * @param observed Whether the response variable is observed
     */
    private void sampleLevelForToken(
            int d, int s, int n,
            boolean remove, boolean add,
            boolean observed) {
        STable curTable = c[d][s];
        SNode[] curPath = getPathFromNode(curTable.getContent());

        if (observed) {
            docTopicWeights[d] -= curPath[z[d][s][n]].getRegressionParameter();
        }

        if (remove) {
            curTable.decrementLevelCount(z[d][s][n]);
            sentLevelCounts[d][s][z[d][s][n]]--;
            curPath[z[d][s][n]].getContent().decrement(words[d][s][n]);
        }

        double[] logprobs = new double[L];
        for (int l = 0; l < L; l++) {
            double logPrior = curTable.getLevelDistribution().getLogProbability(l);
            double wordLlh = curPath[l].getLogProbability(words[d][s][n]);
            double resLlh = 0.0;
            if (observed) {
                double sum = docTopicWeights[d] + docLexicalWeights[d]
                        + curPath[l].getRegressionParameter();
                double mean = sum / docTokenCounts[d];
                resLlh = StatisticsUtils.logNormalProbability(responses[d], mean, sqrtRho);
            }
            logprobs[l] = logPrior + wordLlh + resLlh;
        }

        int sampledL = SamplerUtils.logMaxRescaleSample(logprobs);

        if (z[d][s][n] != sampledL) {
            numTokenAsgnsChange++;
        }

        // update and increment
        z[d][s][n] = sampledL;

        if (add) {
            curTable.incrementLevelCount(z[d][s][n]);
            sentLevelCounts[d][s][z[d][s][n]]++;
            curPath[z[d][s][n]].getContent().increment(words[d][s][n]);
        }

        if (observed) {
            docTopicWeights[d] += curPath[z[d][s][n]].getRegressionParameter();
        }
    }

    /**
     * Sample a table assignment for a sentence
     *
     * @param d The document index
     * @param s The sentence index
     * @param remove Whether the current assignment should be removed
     * @param add Whether the new assignment should be added
     * @param observed Whether the response is observed
     * @param extend Whether the structure is extendable
     */
    private void sampleTableForSentence(int d, int s,
            boolean remove, boolean add,
            boolean observed, boolean extend) {
        STable curTable = c[d][s];

        SparseCount[] sentObsCountPerLevel = getSentObsCountPerLevel(d, s);

        if (observed) {
            this.docTopicWeights[d] -= computeTopicWeight(d, s);
        }

        if (remove) {
            curTable.decreaseLevelCounts(sentLevelCounts[d][s]);
            removeObservationsFromPath(c[d][s].getContent(), sentObsCountPerLevel);
            localRestaurants[d].removeCustomerFromTable(s, c[d][s].getIndex());
            if (c[d][s].isEmpty()) {
                removeTableFromPath(c[d][s].getContent());
                localRestaurants[d].removeTable(c[d][s].getIndex());
            }
        }

        ArrayList<Integer> tableIndices = new ArrayList<Integer>();
        ArrayList<Double> logProbs = new ArrayList<Double>();

        // existing tables
        for (STable table : localRestaurants[d].getTables()) {
            double logprior = Math.log(table.getNumCustomers());
            SNode[] path = getPathFromNode(table.getContent());
            double wordLlh = 0.0;
            for (int l = 0; l < L; l++) {
                for (int obs : sentObsCountPerLevel[l].getIndices()) {
                    wordLlh += path[l].getLogProbability(obs) * sentObsCountPerLevel[l].getCount(obs);
                }
            }

            // log prob of the stick breaking at this table
            double stickLp = table.getLevelDistribution().getLogProbability(sentLevelCounts[d][s]);

            double resLlh = 0.0;
            if (observed) {
                double addTopicWeight = 0.0;
                for (int l = 0; l < L; l++) {
                    addTopicWeight += path[l].getRegressionParameter() * sentLevelCounts[d][s][l];
                }

                double mean = (docTopicWeights[d] + docLexicalWeights[d] + addTopicWeight) / docTokenCounts[d];
                resLlh = StatisticsUtils.logNormalProbability(responses[d], mean, sqrtRho);
            }

            double lp = logprior + wordLlh + resLlh + stickLp;
            logProbs.add(lp);
            tableIndices.add(table.getIndex());

            // debug
//            logln("iter = " + iter + ". d = " + d + ". s = " + s
//                    + ". table: " + table.toString()
//                    + ". log prior = " + MiscUtils.formatDouble(logprior)
//                    + ". word llh = " + MiscUtils.formatDouble(wordLlh)
//                    + ". res llh = " + MiscUtils.formatDouble(resLlh)
//                    + ". stick llh = " + MiscUtils.formatDouble(stickLp)
//                    + ". lp = " + MiscUtils.formatDouble(lp));
        }

        // new table
        HashMap<SNode, Double> pathLogPriors = new HashMap<SNode, Double>();
        HashMap<SNode, Double> pathWordLlhs = new HashMap<SNode, Double>();
        HashMap<SNode, Double> pathResLlhs = new HashMap<SNode, Double>();
        if (extend) {
            // log priors
            computePathLogPrior(pathLogPriors, globalTreeRoot, 0.0);

            // word log likelihoods
            computePathWordLogLikelihood(pathWordLlhs,
                    globalTreeRoot,
                    sentObsCountPerLevel,
                    0.0);

            // debug
            if (pathLogPriors.size() != pathWordLlhs.size()) {
                throw new RuntimeException("Numbers of paths mismatch");
            }

            // response log likelihoods
            if (observed) {
                pathResLlhs = computePathResponseLogLikelihood(d, s);

                // debug
                if (pathLogPriors.size() != pathResLlhs.size()) {
                    throw new RuntimeException("Numbers of paths mismatch");
                }
            }

            double logPrior = logAlpha;
            double marginals = computeMarginals(pathLogPriors, pathWordLlhs, pathResLlhs, observed);

            double newStickLogProb = this.emptyStick.getLogProbability(sentLevelCounts[d][s]);
            double lp = logPrior + marginals + newStickLogProb;
            logProbs.add(lp);
            tableIndices.add(PSEUDO_TABLE_INDEX);

            // debug
//            logln("iter = " + iter + ". d = " + d + ". s = " + s
//                    + ". new table"
//                    + ". log prior = " + MiscUtils.formatDouble(logPrior)
//                    + ". new stick = " + MiscUtils.formatDouble(newStickLogProb)
//                    + ". marginal = " + MiscUtils.formatDouble(marginals)
//                    + ". lp = " + MiscUtils.formatDouble(lp));
        }

        // sample
        int sampledIndex = SamplerUtils.logMaxRescaleSample(logProbs);
        int tableIdx = tableIndices.get(sampledIndex);

        // debug
//        logln(">>> idx = " + sampledIndex + ". tabIdx = " + tableIdx + "\n\n");

        if (curTable != null && curTable.getIndex() != tableIdx) {
            numSentAsntsChange++;
        }

        STable table;
        if (tableIdx == PSEUDO_NODE_INDEX) {
            int newTableIdx = localRestaurants[d].getNextTableIndex();
            table = new STable(iter, newTableIdx, null, d,
                    new TruncatedStickBreaking(L, hyperparams.get(GEM_MEAN),
                    hyperparams.get(GEM_SCALE)));
            localRestaurants[d].addTable(table);

            SNode newNode = samplePath(pathLogPriors, pathWordLlhs, pathResLlhs, observed);
            if (!isLeafNode(newNode)) {
                newNode = createNewPath(newNode);
            }
            table.setContent(newNode);
            addTableToPath(table.getContent());
        } else {
            table = localRestaurants[d].getTable(tableIdx);
        }

        // debug
//        logln("---> assigned table: " + table.toString());

        c[d][s] = table;

        if (add) {
            table.increaseLevelCounts(sentLevelCounts[d][s]);
            addObservationsToPath(table.getContent(), sentObsCountPerLevel);
            localRestaurants[d].addCustomerToTable(s, table.getIndex());
        }

        if (observed) {
            docTopicWeights[d] += computeTopicWeight(d, s);
        }
    }

    /**
     * Sample a path on the global tree for a table
     *
     * @param d The restaurant index
     * @param table The table
     * @param remove Whether the current assignment should be removed
     * @param add Whether the new assignment should be added
     * @param observed Whether the response variable is observed
     * @param extend Whether the global tree is extendable
     */
    private void samplePathForTable(int d, STable table,
            boolean remove, boolean add,
            boolean observed, boolean extend) {
        SNode curLeaf = table.getContent();

        // observation counts of this table per level
        SparseCount[] tabObsCountPerLevel = getTableObsCountPerLevel(d, table);

        if (observed) {
            for (int s : table.getCustomers()) {
                docTopicWeights[d] -= computeTopicWeight(d, s);
            }
        }

        if (remove) {
            removeObservationsFromPath(table.getContent(), tabObsCountPerLevel);
            removeTableFromPath(table.getContent());
        }

        // log priors
        HashMap<SNode, Double> pathLogPriors = new HashMap<SNode, Double>();
        computePathLogPrior(pathLogPriors, globalTreeRoot, 0.0);

        // word log likelihoods
        HashMap<SNode, Double> pathWordLlhs = new HashMap<SNode, Double>();
        computePathWordLogLikelihood(pathWordLlhs, globalTreeRoot, tabObsCountPerLevel, 0.0);

        // debug
        if (pathLogPriors.size() != pathWordLlhs.size()) {
            throw new RuntimeException("Numbers of paths mismatch");
        }

        // response log likelihoods
        HashMap<SNode, Double> pathResLlhs = new HashMap<SNode, Double>();
        if (observed) {
            pathResLlhs = computePathResponseLogLikelihood(d, table);

            if (pathLogPriors.size() != pathResLlhs.size()) {
                throw new RuntimeException("Numbers of paths mismatch");
            }
        }

        // sample
        ArrayList<SNode> pathList = new ArrayList<SNode>();
        ArrayList<Double> logProbs = new ArrayList<Double>();
        for (SNode path : pathLogPriors.keySet()) {
            if (!extend && !isLeafNode(path)) {
                continue;
            }

            double lp = pathLogPriors.get(path) + pathWordLlhs.get(path);
            if (observed) {
                lp += pathResLlhs.get(path);
            }

            logProbs.add(lp);
            pathList.add(path);
        }
        int sampledIndex = SamplerUtils.logMaxRescaleSample(logProbs);
        SNode newLeaf = pathList.get(sampledIndex);

        // debug
        if (curLeaf == null || curLeaf.equals(newLeaf)) {
            numTableAsgnsChange++;
        }

        // if pick an internal node, create the path from the internal node to leave
        if (newLeaf.getLevel() < L - 1) {
            newLeaf = this.createNewPath(newLeaf);
        }

        // update
        table.setContent(newLeaf);

        if (add) {
            addTableToPath(newLeaf);
            addObservationsToPath(newLeaf, tabObsCountPerLevel);
        }

        if (observed) {
            for (int s : table.getCustomers()) {
                docTopicWeights[d] += computeTopicWeight(d, s);
            }
        }
    }

    /**
     * Sample topics of each tree node
     */
    private void sampleTopics() {
        // get all leaves of the tree
        ArrayList<SNode> leaves = new ArrayList<SNode>();
        Stack<SNode> stack = new Stack<SNode>();
        stack.add(globalTreeRoot);
        while (!stack.isEmpty()) {
            SNode node = stack.pop();
            if (node.getChildren().isEmpty()) {
                leaves.add(node);
            }
            for (SNode child : node.getChildren()) {
                stack.add(child);
            }
        }

        // bottom-up smoothing to compute pseudo-counts from children
        Queue<SNode> queue = new LinkedList<SNode>();
        for (SNode leaf : leaves) {
            queue.add(leaf);
        }
        while (!queue.isEmpty()) {
            SNode node = queue.poll();
            if (node.equals(globalTreeRoot)) {
                break;
            }

            SNode parent = node.getParent();
            if (!queue.contains(parent)) {
                queue.add(parent);
            }

            if (node.isLeaf()) {
                continue;
            }

            if (this.pathAssumption == PathAssumption.MINIMAL) {
                node.getPseudoCountsFromChildrenMin();
            } else if (this.pathAssumption == PathAssumption.MAXIMAL) {
                node.getPseudoCountsFromChildrenMax();
            } else {
                throw new RuntimeException("Path assumption " + this.pathAssumption
                        + " is not supported.");
            }
        }

        // top-down sampling to get topics
        queue = new LinkedList<SNode>();
        queue.add(globalTreeRoot);
        while (!queue.isEmpty()) {
            SNode node = queue.poll();
            for (SNode child : node.getChildren()) {
                queue.add(child);
            }

            node.sampleTopic(betas[node.getLevel()], betas[node.getLevel()]);
        }
    }

    /**
     * Optimize lexical regression parameters.
     */
    private void optimizeLexicalRegressionParameters() {
        // adjusted response vector
        double[] responseVector = new double[D];
        for (int d = 0; d < D; d++) {
            responseVector[d] = responses[d] - docTopicWeights[d] / docTokenCounts[d];
        }

        GurobiMLRL2Norm mlr =
                new GurobiMLRL2Norm(this.docLexicalDesignMatrix, responseVector, 1.0);
        double[] weights = mlr.solve();
        for (int ii = 0; ii < weights.length; ii++) {
            int v = this.lexicalList.get(ii);
            this.lexicalWeights.set(v, weights[ii]);
        }
        this.updateDocumentLexicalWeights();
    }

    /**
     * Optimize topic regression parameters.
     */
    private void optimizeTopicRegressionParameters() {
        ArrayList<SNode> flattenTree = flattenTreeWithoutRoot();
        int numNodes = flattenTree.size();

        double[] lambdas = new double[numNodes];
        HashMap<SNode, Integer> nodeIndices = new HashMap<SNode, Integer>();
        for (int i = 0; i < flattenTree.size(); i++) {
            SNode node = flattenTree.get(i);
            nodeIndices.put(node, i);
            lambdas[i] = 1.0 / sigmas[node.getLevel()];
        }

        // design matrix
        double[][] designMatrix = new double[D][numNodes];
        for (int d = 0; d < D; d++) {
            for (int s = 0; s < words[d].length; s++) {
                SNode[] path = getPathFromNode(c[d][s].getContent());
                for (int l = 1; l < L; l++) {
                    int nodeIdx = nodeIndices.get(path[l]);
                    int count = sentLevelCounts[d][s][l];
                    designMatrix[d][nodeIdx] += count;
                }
            }

            for (int i = 0; i < numNodes; i++) {
                designMatrix[d][i] /= docTokenCounts[d];
            }
        }

        // adjusted response vector
        double[] responseVector = new double[D];
        for (int d = 0; d < D; d++) {
            responseVector[d] = responses[d] - docLexicalWeights[d] / docTokenCounts[d];
        }

        GurobiMLRL2Norm mlr =
                new GurobiMLRL2Norm(designMatrix, responseVector, lambdas);
        double[] weights = mlr.solve();

        // update
        for (int i = 0; i < numNodes; i++) {
            flattenTree.get(i).setRegressionParameter(weights[i]);
        }
        this.updateDocumentTopicWeights();
    }

    private ArrayList<SNode> flattenTreeWithoutRoot() {
        ArrayList<SNode> flattenTree = new ArrayList<SNode>();
        Stack<SNode> stack = new Stack<SNode>();
        stack.add(globalTreeRoot);
        while (!stack.isEmpty()) {
            SNode node = stack.pop();
            if (!node.isRoot()) {
                flattenTree.add(node);
            }
            for (SNode child : node.getChildren()) {
                stack.add(child);
            }
        }
        return flattenTree;
    }

    /**
     * Sample a path
     *
     * @param logPriors Path log priors
     * @param wordLlhs Path word log likelihoods
     * @param resLlhs Path response variable log likelihoods
     */
    private SNode samplePath(
            HashMap<SNode, Double> logPriors,
            HashMap<SNode, Double> wordLlhs,
            HashMap<SNode, Double> resLlhs,
            boolean observed) {
        ArrayList<SNode> pathList = new ArrayList<SNode>();
        ArrayList<Double> logProbs = new ArrayList<Double>();
        for (SNode node : logPriors.keySet()) {
            double lp = logPriors.get(node) + wordLlhs.get(node);
            if (observed) {
                lp += resLlhs.get(node);
            }
            pathList.add(node);
            logProbs.add(lp);

            // debug
//            if (observed) {
//                logln("--- " + (pathList.size() - 1)
//                        + ". " + node.toString()
//                        + ". logprior: " + MiscUtils.formatDouble(logPriors.get(node))
//                        + ". wordllh: " + MiscUtils.formatDouble(wordLlhs.get(node))
//                        + ". resllh: " + MiscUtils.formatDouble(resLlhs.get(node))
//                        + ". lp: " + MiscUtils.formatDouble(lp));
//            } else {
//                logln("--- " + (pathList.size() - 1)
//                        + ". " + node.toString()
//                        + ". logprior: " + MiscUtils.formatDouble(logPriors.get(node))
//                        + ". wordllh: " + MiscUtils.formatDouble(wordLlhs.get(node))
//                        + ". lp: " + MiscUtils.formatDouble(lp));
//            }
        }

        int sampledIndex = SamplerUtils.logMaxRescaleSample(logProbs);
        SNode path = pathList.get(sampledIndex);

        // debug
//        logln("--- >>> sampler idx: " + sampledIndex
//                + ". " + path.toString()
//                + "\n\n");

        return path;
    }

    /**
     * Compute the log probability of a new table, marginalized over all
     * possible paths
     *
     * @param pathLogPriors The log priors of each path
     * @param pathWordLogLikelihoods The word likelihoods
     * @param pathResLogLikelihoods The response variable likelihoods
     */
    private double computeMarginals(
            HashMap<SNode, Double> pathLogPriors,
            HashMap<SNode, Double> pathWordLogLikelihoods,
            HashMap<SNode, Double> pathResLogLikelihoods,
            boolean resObserved) {
        double marginal = 0.0;
        for (SNode node : pathLogPriors.keySet()) {
            double logprior = pathLogPriors.get(node);
            double loglikelihood = pathWordLogLikelihoods.get(node);

            double lp = logprior + loglikelihood;
            if (resObserved) {
                lp += pathResLogLikelihoods.get(node);
            }

            if (marginal == 0.0) {
                marginal = lp;
            } else {
                marginal = SamplerUtils.logAdd(marginal, lp);
            }
        }
        return marginal;
    }

    /**
     * Compute the log probability of the response variable when the given table
     * is assigned to each path
     *
     * @param d The document index
     * @param table The table
     */
    private HashMap<SNode, Double> computePathResponseLogLikelihood(
            int d,
            STable table) {
        HashMap<SNode, Double> resLlhs = new HashMap<SNode, Double>();

        Stack<SNode> stack = new Stack<SNode>();
        stack.add(globalTreeRoot);
        while (!stack.isEmpty()) {
            SNode node = stack.pop();

            SNode[] path = getPathFromNode(node);
            double addSum = 0.0;
            double var = hyperparams.get(RHO);
            int level;
            for (level = 0; level < path.length; level++) {
                for (int s : table.getCustomers()) {
                    addSum += path[level].getRegressionParameter() * sentLevelCounts[d][s][level];
                }
            }
            while (level < L) {
                int totalLevelCount = 0;
                for (int s : table.getCustomers()) {
                    int levelCount = sentLevelCounts[d][s][level];
                    addSum += levelCount * mus[level];
                    totalLevelCount += levelCount;
                }
                var += Math.pow((double) totalLevelCount / docTokenCounts[d], 2) * sigmas[level];
                level++;
            }

            double mean = (docTopicWeights[d] + docLexicalWeights[d] + addSum) / docTokenCounts[d];
            double resLlh = StatisticsUtils.logNormalProbability(responses[d], mean, Math.sqrt(var));
            resLlhs.put(node, resLlh);

            for (SNode child : node.getChildren()) {
                stack.add(child);
            }
        }
        return resLlhs;
    }

    /**
     * Compute the log probability of the response variable when the given
     * sentence is assigned to each path
     *
     * @param d The document index
     * @param s The sentence index
     */
    private HashMap<SNode, Double> computePathResponseLogLikelihood(int d, int s) {
        HashMap<SNode, Double> resLlhs = new HashMap<SNode, Double>();

        Stack<SNode> stack = new Stack<SNode>();
        stack.add(globalTreeRoot);
        while (!stack.isEmpty()) {
            SNode node = stack.pop();

            SNode[] path = getPathFromNode(node);
            double addTopicWeight = 0.0;
            double var = hyperparams.get(RHO);
            int level;
            for (level = 0; level < path.length; level++) {
                addTopicWeight += path[level].getRegressionParameter() * sentLevelCounts[d][s][level];
            }

            while (level < L) {
                int levelCount = sentLevelCounts[d][s][level];
                addTopicWeight += levelCount * mus[level];
                var += Math.pow((double) levelCount / docTokenCounts[d], 2) * sigmas[level];
                level++;
            }

            // note: the topic weight of the current sentence s has been excluded
            // from docTopicWeights[d]
            double mean = (docTopicWeights[d] + docLexicalWeights[d] + addTopicWeight) / docTokenCounts[d];
            double resLlh = StatisticsUtils.logNormalProbability(responses[d], mean, Math.sqrt(var));
            resLlhs.put(node, resLlh);

            for (SNode child : node.getChildren()) {
                stack.add(child);
            }
        }

        return resLlhs;
    }

    /**
     * Compute the log probability of assigning a set of words to each path in
     * the tree
     *
     * @param nodeDataLlhs HashMap to store the result
     * @param curNode The current node in recursive calls
     * @param tokenCountPerLevel Token counts per level
     * @param parentDataLlh The value passed from the parent node
     */
    private void computePathWordLogLikelihood(
            HashMap<SNode, Double> nodeDataLlhs,
            SNode curNode,
            SparseCount[] tokenCountPerLevel,
            double parentDataLlh) {

        int level = curNode.getLevel();
        double nodeDataLlh = curNode.getLogProbability(tokenCountPerLevel[level]);

        // populate to child nodes
        for (SNode child : curNode.getChildren()) {
            computePathWordLogLikelihood(nodeDataLlhs, child, tokenCountPerLevel,
                    parentDataLlh + nodeDataLlh);
        }

        // store the data llh from the root to this current node
        double storeDataLlh = parentDataLlh + nodeDataLlh;
        level++;
        while (level < L) { // if this is an internal node, add llh of new child node
            DirMult dirMult;
            if (curNode.getTopic() == null) {
                dirMult = new DirMult(V, betas[level] * V, 1.0 / V);
            } else {
                dirMult = new DirMult(V, betas[level] * V, curNode.getTopic());
            }
            storeDataLlh += dirMult.getLogLikelihood(tokenCountPerLevel[level].getObservations());
            level++;
        }
        nodeDataLlhs.put(curNode, storeDataLlh);
    }

    /**
     * Recursively compute the log probability of each path in the global tree
     *
     * @param nodeLogProbs HashMap to store the results
     * @param curNode Current node in the recursive call
     * @param parentLogProb The log probability passed from the parent node
     */
    private void computePathLogPrior(
            HashMap<SNode, Double> nodeLogProbs,
            SNode curNode,
            double parentLogProb) {
        double newWeight = parentLogProb;
        if (!isLeafNode(curNode)) {
            double logNorm = Math.log(curNode.getNumCustomers() + gammas[curNode.getLevel()]);
            newWeight += logGammas[curNode.getLevel()] - logNorm;

            for (SNode child : curNode.getChildren()) {
                double childWeight = parentLogProb + Math.log(child.getNumCustomers()) - logNorm;
                computePathLogPrior(nodeLogProbs, child, childWeight);
            }
        }
        nodeLogProbs.put(curNode, newWeight);
    }

    /**
     * Compute the regression sum from the topic tree for a sentence
     *
     * @param d The document index
     * @param s The sentence index
     * @return The regression sum of the sentence
     */
    private double computeTopicWeight(int d, int s) {
        double regSum = 0.0;
        SNode[] path = getPathFromNode(c[d][s].getContent());
        for (int l = 0; l < path.length; l++) {
            regSum += path[l].getRegressionParameter() * sentLevelCounts[d][s][l];
        }
        return regSum;
    }

    /**
     * Return a path from the root to a given node
     *
     * @param node The given node
     * @return An array containing the path
     */
    private SNode[] getPathFromNode(SNode node) {
        SNode[] path = new SNode[node.getLevel() + 1];
        SNode curNode = node;
        int l = node.getLevel();
        while (curNode != null) {
            path[l--] = curNode;
            curNode = curNode.getParent();
        }
        return path;
    }

    /**
     * Check whether a given node is a leaf node.
     *
     * @param node The node
     */
    private boolean isLeafNode(SNode node) {
        return node.getLevel() == L - 1;
    }

    /**
     * Parse the node path string.
     *
     * @param nodePath The node path string
     */
    public int[] parseNodePath(String nodePath) {
        String[] ss = nodePath.split(":");
        int[] parsedPath = new int[ss.length];
        for (int i = 0; i < ss.length; i++) {
            parsedPath[i] = Integer.parseInt(ss[i]);
        }
        return parsedPath;
    }

    /**
     * Get a node in the tree given a parsed path
     *
     * @param parsedPath The parsed path
     */
    private SNode getNode(int[] parsedPath) {
        SNode node = globalTreeRoot;
        for (int i = 1; i < parsedPath.length; i++) {
            node = node.getChild(parsedPath[i]);
        }
        return node;
    }

    private void outputWeights(File outputFile, double[] ws) throws Exception {
        if (verbose) {
            logln("--- Writing weights to file " + outputFile);
        }
        BufferedWriter writer = IOUtils.getBufferedWriter(outputFile);
        for (int ii = 0; ii < ws.length; ii++) {
            writer.write(wordVocab.get(ii) + "\t" + ws[ii] + "\n");
        }
        writer.close();
    }

    private double[] inputWeights(File inputFile) throws Exception {
        if (verbose) {
            logln("--- Reading weights from file " + inputFile);
        }
        double[] ws = new double[V];
        BufferedReader reader = IOUtils.getBufferedReader(inputFile);
        for (int v = 0; v < V; v++) {
            ws[v] = Double.parseDouble(reader.readLine().split("\t")[1]);
        }
        reader.close();
        return ws;
    }

    public String printGlobalTreeSummary() {
        StringBuilder str = new StringBuilder();
        int[] nodeCountPerLevel = new int[L];
        int[] obsCountPerLevel = new int[L];

        Stack<SNode> stack = new Stack<SNode>();
        stack.add(globalTreeRoot);

        int totalObs = 0;
        while (!stack.isEmpty()) {
            SNode node = stack.pop();
            nodeCountPerLevel[node.getLevel()]++;
            obsCountPerLevel[node.getLevel()] += node.getContent().getCountSum();

            totalObs += node.getContent().getCountSum();

            for (SNode child : node.getChildren()) {
                stack.add(child);
            }
        }
        str.append("global tree:\n\t>>> node count per level: ");
        for (int l = 0; l < L; l++) {
            str.append(l).append("(")
                    .append(nodeCountPerLevel[l])
                    .append(", ").append(obsCountPerLevel[l])
                    .append(");\t");
        }
        str.append("\n");
        str.append("\t>>> # observations = ").append(totalObs)
                .append("\n\t>>> # customers = ").append(globalTreeRoot.getNumCustomers());
        return str.toString();
    }

    public String printGlobalTree() {
        StringBuilder str = new StringBuilder();
        str.append("global tree\n");

        Stack<SNode> stack = new Stack<SNode>();
        stack.add(globalTreeRoot);

        int totalObs = 0;

        while (!stack.isEmpty()) {
            SNode node = stack.pop();

            for (int i = 0; i < node.getLevel(); i++) {
                str.append("\t");
            }
            str.append(node.toString())
                    .append("\n");

            totalObs += node.getContent().getCountSum();

            for (SNode child : node.getChildren()) {
                stack.add(child);
            }
        }
        str.append(">>> # observations = ").append(totalObs)
                .append("\n>>> # customers = ").append(globalTreeRoot.getNumCustomers())
                .append("\n");
        return str.toString();
    }

    public String printLocalRestaurantSummary() {
        StringBuilder str = new StringBuilder();
        str.append("local restaurants:\n");
        int[] numTables = new int[D];
        int totalTableCusts = 0;
        for (int d = 0; d < D; d++) {
            numTables[d] = localRestaurants[d].getNumTables();
            for (STable table : localRestaurants[d].getTables()) {
                totalTableCusts += table.getNumCustomers();
            }
        }
        str.append("\t>>> # tables:")
                .append(". min: ").append(MiscUtils.formatDouble(StatisticsUtils.min(numTables)))
                .append(". max: ").append(MiscUtils.formatDouble(StatisticsUtils.max(numTables)))
                .append(". avg: ").append(MiscUtils.formatDouble(StatisticsUtils.mean(numTables)))
                .append(". total: ").append(MiscUtils.formatDouble(StatisticsUtils.sum(numTables)))
                .append("\n");
        str.append("\t>>> # customers: ").append(totalTableCusts);
        return str.toString();
    }

    public String printLocalRestaurants() {
        StringBuilder str = new StringBuilder();
        for (int d = 0; d < D; d++) {
            logln("restaurant d = " + d
                    + ". # tables: " + localRestaurants[d].getNumTables()
                    + ". # total customers: " + localRestaurants[d].getTotalNumCustomers());
            for (STable table : localRestaurants[d].getTables()) {
                logln("--- table: " + table.toString());
            }
            System.out.println();
        }
        return str.toString();
    }

    public String printLocalRestaurant(int d) {
        StringBuilder str = new StringBuilder();
        str.append("restaurant d = ").append(d)
                .append(". # tables: ").append(localRestaurants[d].getNumTables())
                .append(". # total customers: ").append(localRestaurants[d].getTotalNumCustomers()).append("\n");
        for (STable table : localRestaurants[d].getTables()) {
            str.append("--- table: ").append(table.toString()).append("\n");
        }
        return str.toString();
    }

    @Override
    public void validate(String msg) {
        logln("Validating ... " + msg);

        validateModel(msg);

        validateAssignments(msg);
    }

    private void validateModel(String msg) {
        Stack<SNode> stack = new Stack<SNode>();
        stack.add(globalTreeRoot);
        while (!stack.isEmpty()) {
            SNode node = stack.pop();

            if (!isLeafNode(node)) {
                int childNumCusts = 0;

                for (SNode child : node.getChildren()) {
                    childNumCusts += child.getNumCustomers();
                    stack.add(child);
                }

                if (childNumCusts != node.getNumCustomers()) {
                    throw new RuntimeException(msg + ". Numbers of customers mismatch. "
                            + node.toString());
                }
            }

            if (this.isLeafNode(node) && node.isEmpty()) {
                throw new RuntimeException(msg + ". Leaf node " + node.toString()
                        + " is empty");
            }
        }
    }

    private void validateAssignments(String msg) {
        for (int d = 0; d < D; d++) {
            int totalCusts = 0;
            for (STable table : localRestaurants[d].getTables()) {
                table.getLevelDistribution().validate(msg);
                totalCusts += table.getNumCustomers();
            }
            if (totalCusts != words[d].length) {
                for (STable table : localRestaurants[d].getTables()) {
                    System.out.println(table.toString() + ". customers: " + table.getCustomers().toString());
                }
                throw new RuntimeException(msg + ". Numbers of customers in restaurant " + d
                        + " mismatch. " + totalCusts + " vs. " + words[d].length);
            }

            HashMap<STable, Integer> tableCustCounts = new HashMap<STable, Integer>();
            for (int s = 0; s < words[d].length; s++) {
                Integer count = tableCustCounts.get(c[d][s]);

                if (count == null) {
                    tableCustCounts.put(c[d][s], 1);
                } else {
                    tableCustCounts.put(c[d][s], count + 1);
                }
            }

            if (tableCustCounts.size() != localRestaurants[d].getNumTables()) {
                throw new RuntimeException(msg + ". Numbers of tables mismatch in"
                        + " restaurant " + d);
            }

            for (STable table : localRestaurants[d].getTables()) {
                if (table.getNumCustomers() != tableCustCounts.get(table)) {
                    System.out.println("Table: " + table.toString());

                    for (int s : table.getCustomers()) {
                        System.out.println("--- s = " + s + ". " + c[d][s].toString());
                    }
                    System.out.println(tableCustCounts.get(table));


                    throw new RuntimeException(msg + ". Number of customers "
                            + "mismatch. Table " + table.toString()
                            + ". " + table.getNumCustomers() + " vs. " + tableCustCounts.get(table));
                }
            }
        }

        if (docTopicWeights != null) {
            for (int d = 0; d < D; d++) {
                double topicWeight = 0.0;
                for (int s = 0; s < words[d].length; s++) {
                    topicWeight += computeTopicWeight(d, s);
                }
                if (Math.abs(topicWeight - docTopicWeights[d]) > 0.01) {
                    throw new RuntimeException(msg + ". Topic weights of document " + d
                            + " mismatch. " + topicWeight + " vs. " + docTokenCounts[d]);
                }
            }
        }

        for (int d = 0; d < D; d++) {
            for (STable table : localRestaurants[d].getTables()) {
                int[] countPerLevel = new int[L];
                for (int s : table.getCustomers()) {
                    for (int ll = 0; ll < L; ll++) {
                        countPerLevel[ll] += sentLevelCounts[d][s][ll];
                    }
                }

                for (int ll = 0; ll < L; ll++) {
                    if (countPerLevel[ll] != table.getLevelDistribution().getCount(ll)) {
                        throw new RuntimeException(msg + ". Count at level " + ll
                                + " in doc " + d + ", table " + table.getTableId()
                                + " mismatch. " + countPerLevel[ll] + " vs. "
                                + table.getLevelDistribution().getCount(ll));
                    }
                }
            }
        }
    }

    @Override
    public double getLogLikelihood() {
        double wordLlh = 0.0;
        double treeLogProb = 0.0;
        double regParamLgprob = 0.0;
        Stack<SNode> stack = new Stack<SNode>();
        stack.add(globalTreeRoot);
        while (!stack.isEmpty()) {
            SNode node = stack.pop();

            wordLlh += node.getContent().getLogLikelihood();

            regParamLgprob += StatisticsUtils.logNormalProbability(node.getRegressionParameter(),
                    mus[node.getLevel()], Math.sqrt(sigmas[node.getLevel()]));

            if (!isLeafNode(node)) {
                treeLogProb += node.getLogJointProbability(gammas[node.getLevel()]);
            }

            for (SNode child : node.getChildren()) {
                stack.add(child);
            }
        }

        double stickLgprob = 0.0;
        double resLlh = 0.0;
        double restLgprob = 0.0;
        double[] regValues = getRegressionValues();
        for (int d = 0; d < D; d++) {
            for (STable table : localRestaurants[d].getTables()) {
                stickLgprob += table.getLevelDistribution().getLogLikelihood();
            }

            restLgprob += localRestaurants[d].getJointProbabilityAssignments(hyperparams.get(ALPHA));

            resLlh += StatisticsUtils.logNormalProbability(responses[d],
                    regValues[d], sqrtRho);
        }

        logln("^^^ word-llh = " + MiscUtils.formatDouble(wordLlh)
                + ". tree = " + MiscUtils.formatDouble(treeLogProb)
                + ". rest = " + MiscUtils.formatDouble(restLgprob)
                + ". stick = " + MiscUtils.formatDouble(stickLgprob)
                + ". reg param = " + MiscUtils.formatDouble(regParamLgprob)
                + ". response = " + MiscUtils.formatDouble(resLlh));

        double llh = wordLlh + treeLogProb + stickLgprob + regParamLgprob + resLlh + restLgprob;
        return llh;
    }

    @Override
    public double getLogLikelihood(ArrayList<Double> tParams) {
        return 0.0;
    }

    @Override
    public void updateHyperparameters(ArrayList<Double> tParams) {
    }

    @Override
    public String getCurrentState() {
        StringBuilder str = new StringBuilder();
        str.append(printGlobalTreeSummary()).append("\n");
        str.append(printLocalRestaurantSummary()).append("\n");
        return str.toString();
    }

    @Override
    public void output(File samplerFile) {
        this.outputState(samplerFile.getAbsolutePath());
    }

    @Override
    public void input(File samplerFile) {
        this.inputModel(samplerFile.getAbsolutePath());
    }

    public void inputFinalModel() {
        File reportFolder = new File(getSamplerFolderPath(), ReportFolder);
        this.inputModel(new File(reportFolder, "iter-" + MAX_ITER + ".zip").getAbsolutePath());
    }

    public void inputFinalState() {
        File reportFolder = new File(getSamplerFolderPath(), ReportFolder);
        this.inputState(new File(reportFolder, "iter-" + MAX_ITER + ".zip"));
    }

    @Override
    public void outputState(String filepath) {
        if (verbose) {
            logln("--- Outputing current state to " + filepath + "\n");
        }

        try {
            // model
            StringBuilder modelStr = new StringBuilder();
            modelStr.append(SparseVector.output(lexicalWeights)).append("\n");

            Stack<SNode> stack = new Stack<SNode>();
            stack.add(globalTreeRoot);
            while (!stack.isEmpty()) {
                SNode node = stack.pop();
                modelStr.append(node.getPathString()).append("\n");
                modelStr.append(node.getIterationCreated()).append("\n");
                modelStr.append(node.getNumCustomers()).append("\n");
                modelStr.append(node.getRegressionParameter()).append("\n");
                modelStr.append(DirMult.output(node.getContent())).append("\n");
                modelStr.append(DirMult.outputDistribution(node.getContent().getSamplingDistribution())).append("\n");

                for (SNode child : node.getChildren()) {
                    stack.add(child);
                }
            }

            // assignments
            StringBuilder assignStr = new StringBuilder();
            for (int d = 0; d < D; d++) {
                assignStr.append(d)
                        .append("\t").append(localRestaurants[d].getNumTables())
                        .append("\n");
                for (STable table : localRestaurants[d].getTables()) {
                    assignStr.append(table.getIndex()).append("\n");
                    assignStr.append(table.getIterationCreated()).append("\n");
                    assignStr.append(table.getContent().getPathString()).append("\n");
                    assignStr.append(TruncatedStickBreaking.output(table.levelDist)).append("\n");
                }
            }

            for (int d = 0; d < D; d++) {
                for (int s = 0; s < words[d].length; s++) {
                    assignStr.append(d)
                            .append(":").append(s)
                            .append("\t").append(c[d][s].getIndex())
                            .append("\n");
                }
            }

            for (int d = 0; d < D; d++) {
                for (int t = 0; t < words[d].length; t++) {
                    for (int n = 0; n < words[d][t].length; n++) {
                        assignStr.append(d)
                                .append(":").append(t)
                                .append(":").append(n)
                                .append("\t").append(z[d][t][n])
                                .append("\n");
                    }
                }
            }

            // output to a compressed file
            this.outputZipFile(filepath, modelStr.toString(), assignStr.toString());
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Exception while outputing to " + filepath);
        }
    }

    @Override
    public void inputState(String filepath) {
        if (verbose) {
            logln("--- Reading state from " + filepath + "\n");
        }

        try {
            inputModel(filepath);

            inputAssignments(filepath);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }

        if (debug) {
            validate("--- Loaded.");
        }
    }

    /**
     * Load the model from a compressed state file
     *
     * @param zipFilepath Path to the compressed state file (.zip)
     */
    private void inputModel(String zipFilepath) {
        if (verbose) {
            logln("--- --- Loading model from " + zipFilepath + "\n");
        }

        try {
            // initialize
            this.initializeModelStructure();

            String filename = IOUtils.removeExtension(IOUtils.getFilename(new File(zipFilepath).getName()));
            BufferedReader reader = IOUtils.getBufferedReader(zipFilepath, filename + ModelFileExt);

            // lexical weights
            String line = reader.readLine();
            this.lexicalWeights = SparseVector.input(line);

            // topic tree
            HashMap<String, SNode> nodeMap = new HashMap<String, SNode>();
            while ((line = reader.readLine()) != null) {
                String pathStr = line;
                int iterCreated = Integer.parseInt(reader.readLine());
                int numCustomers = Integer.parseInt(reader.readLine());
                double regParam = Double.parseDouble(reader.readLine());
                DirMult dmm = DirMult.input(reader.readLine());
                double[] dist = DirMult.inputDistribution(reader.readLine());
                dmm.setSamplingDistribution(dist);

                // create node
                int lastColonIndex = pathStr.lastIndexOf(":");
                SNode parent = null;
                if (lastColonIndex != -1) {
                    parent = nodeMap.get(pathStr.substring(0, lastColonIndex));
                }

                String[] pathIndices = pathStr.split(":");
                int nodeIndex = Integer.parseInt(pathIndices[pathIndices.length - 1]);
                int nodeLevel = pathIndices.length - 1;
                SNode node = new SNode(iterCreated, nodeIndex,
                        nodeLevel, dmm, regParam, parent);
                node.setTopic(dist);

                node.changeNumCustomers(numCustomers);

                if (node.getLevel() == 0) {
                    globalTreeRoot = node;
                }

                if (parent != null) {
                    parent.addChild(node.getIndex(), node);
                }

                nodeMap.put(pathStr, node);
            }
            reader.close();

            Stack<SNode> stack = new Stack<SNode>();
            stack.add(globalTreeRoot);
            while (!stack.isEmpty()) {
                SNode node = stack.pop();
                if (!isLeafNode(node)) {
                    node.fillInactiveChildIndices();
                    for (SNode child : node.getChildren()) {
                        stack.add(child);
                    }
                }
            }

            validateModel("Loading model " + filename);
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Exception while loading model from "
                    + zipFilepath);
        }
    }

    /**
     * Load the assignments of the training data from the compressed state file
     *
     * @param zipFilepath Path to the compressed state file (.zip)
     */
    private void inputAssignments(String zipFilepath) throws Exception {
        if (verbose) {
            logln("--- --- Loading assignments from " + zipFilepath + "\n");
        }

        // initialize
        this.initializeDataStructure();

        String filename = IOUtils.removeExtension(IOUtils.getFilename(new File(zipFilepath).getName()));
        BufferedReader reader = IOUtils.getBufferedReader(zipFilepath, filename + AssignmentFileExt);

        String[] sline;

        for (int d = 0; d < D; d++) {
            sline = reader.readLine().split("\t");
            if (d != Integer.parseInt(sline[0])) {
                throw new RuntimeException("Mismatch");
            }
            int numTables = Integer.parseInt(sline[1]);

            for (int i = 0; i < numTables; i++) {
                int tabIndex = Integer.parseInt(reader.readLine());
                int iterCreated = Integer.parseInt(reader.readLine());
                SNode leafNode = getNode(parseNodePath(reader.readLine()));
                TruncatedStickBreaking levelDist = TruncatedStickBreaking.input(reader.readLine());
                STable table = new STable(iterCreated, tabIndex, leafNode, d, levelDist);
                localRestaurants[d].addTable(table);
            }
        }

        for (int d = 0; d < D; d++) {
            localRestaurants[d].fillInactiveTableIndices();
        }

        for (int d = 0; d < D; d++) {
            for (int s = 0; s < words[d].length; s++) {
                sline = reader.readLine().split("\t");
                if (!sline[0].equals(d + ":" + s)) {
                    throw new RuntimeException("Mismatch");
                }
                int tableIndex = Integer.parseInt(sline[1]);
                STable table = localRestaurants[d].getTable(tableIndex);
                c[d][s] = table;
                localRestaurants[d].addCustomerToTable(s, tableIndex);
            }
        }

        for (int d = 0; d < D; d++) {
            for (int s = 0; s < words[d].length; s++) {
                STable table = c[d][s];
                SNode[] path = getPathFromNode(table.getContent());
                for (int n = 0; n < words[d][s].length; n++) {
                    sline = reader.readLine().split("\t");
                    if (!sline[0].equals(d + ":" + s + ":" + n)) {
                        throw new RuntimeException("Mismatch");
                    }
                    z[d][s][n] = Integer.parseInt(sline[1]);
                    path[z[d][s][n]].getContent().increment(words[d][s][n]);
                    sentLevelCounts[d][s][z[d][s][n]]++;
                }
            }
        }

        reader.close();
    }

    public void outputTopicTopWords(File outputFile, int numWords) {
        if (this.wordVocab == null) {
            throw new RuntimeException("The word vocab has not been assigned yet");
        }

        if (verbose) {
            logln("Outputing top words to file " + outputFile);
        }

        StringBuilder str = new StringBuilder();
        Stack<SNode> stack = new Stack<SNode>();
        stack.add(globalTreeRoot);
        while (!stack.isEmpty()) {
            SNode node = stack.pop();

            ArrayList<RankingItem<SNode>> rankChildren = new ArrayList<RankingItem<SNode>>();
            for (SNode child : node.getChildren()) {
                rankChildren.add(new RankingItem<SNode>(child, child.regression));
            }
            Collections.sort(rankChildren);
            for (RankingItem<SNode> item : rankChildren) {
                stack.add(item.getObject());
            }

            // skip leaf nodes that are empty
            if (isLeafNode(node) && node.getContent().getCountSum() == 0) {
                continue;
            }
            if (node.getIterationCreated() >= MAX_ITER - LAG) {
                continue;
            }

            double[] nodeTopic = node.getTopic();
            String[] topWords = getTopWords(nodeTopic, numWords);
            for (int i = 0; i < node.getLevel(); i++) {
                str.append("   ");
            }
            str.append(node.getPathString())
                    .append(" (").append(node.getIterationCreated())
                    .append("; ").append(node.getNumCustomers())
                    .append("; ").append(node.getContent().getCountSum())
                    .append("; ").append(MiscUtils.formatDouble(node.getRegressionParameter()))
                    .append(")");
            for (String topWord : topWords) {
                str.append(" ").append(topWord);
            }
            str.append("\n\n");
        }

        try {
            BufferedWriter writer = IOUtils.getBufferedWriter(outputFile);
            writer.write(str.toString());
            writer.close();
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Exception while outputing topics "
                    + outputFile);
        }
    }

    private HashMap<SNode, ArrayList<RankingItem<String>>> getRankingSentences() {
        HashMap<SNode, ArrayList<RankingItem<String>>> pathRankSentMap =
                new HashMap<SNode, ArrayList<RankingItem<String>>>();
        for (int d = 0; d < D; d++) {
            for (STable table : localRestaurants[d].getTables()) {
                SNode pathNode = table.getContent();
                ArrayList<RankingItem<String>> rankSents = pathRankSentMap.get(pathNode);
                if (rankSents == null) {
                    rankSents = new ArrayList<RankingItem<String>>();
                }
                for (int s : table.getCustomers()) {
                    if (words[d][s].length < 10) { // filter out too short sentences
                        continue;
                    }

                    double logprob = 0.0;
                    for (int n = 0; n < words[d][s].length; n++) {
                        if (z[d][s][n] == L - 1) {
                            logprob += pathNode.getLogProbability(words[d][s][n]);
                        }
                    }
                    if (logprob != 0) {
                        rankSents.add(new RankingItem<String>(d + "-" + s, logprob / words[d][s].length));
                    }
                }
                pathRankSentMap.put(pathNode, rankSents);
            }
        }
        return pathRankSentMap;
    }

    public void outputSentences(File outputFile, String[][] rawSentences, int numSents) {
        if (verbose) {
            logln("--- Outputing sentences to " + outputFile);
        }

        // rank sentences for each path
        HashMap<SNode, ArrayList<RankingItem<String>>> pathRankSentMap = getRankingSentences();

        // print out top sentences
        try {
            BufferedWriter writer = IOUtils.getBufferedWriter(outputFile);
            Stack<SNode> stack = new Stack<SNode>();
            stack.add(globalTreeRoot);
            while (!stack.isEmpty()) {
                SNode node = stack.pop();

                for (SNode child : node.getChildren()) {
                    stack.add(child);
                }

                if (isLeafNode(node)) {
                    double[] nodeTopic = node.getTopic();
                    String[] topWords = getTopWords(nodeTopic, 20);
                    writer.write(node.toString());
                    for (String topWord : topWords) {
                        writer.write("\t" + topWord);
                    }
                    writer.write("\n");

                    ArrayList<RankingItem<String>> rankSents = pathRankSentMap.get(node);
                    Collections.sort(rankSents);
                    for (int ii = 0; ii < Math.min(numSents, rankSents.size()); ii++) {
                        RankingItem<String> sent = rankSents.get(ii);
                        int d = Integer.parseInt(sent.getObject().split("-")[0]);
                        int s = Integer.parseInt(sent.getObject().split("-")[1]);
                        double score = sent.getPrimaryValue();
                        writer.write("\t[" + MiscUtils.formatDouble(score) + "] "
                                + rawSentences[d][s] + "\n");
                    }
                    writer.write("\n");
                }
            }
            writer.close();
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Exception while outputing sentences.");
        }
    }

    public void outputHTML(
            File htmlFile,
            String[] docIds,
            String[][] rawSentences,
            int numSents,
            int numWords) throws Exception {
        if (verbose) {
            logln("--- Outputing result to HTML file " + htmlFile);
        }

        // rank sentences for each path
        HashMap<SNode, ArrayList<RankingItem<String>>> pathRankSentMap = getRankingSentences();

        StringBuilder str = new StringBuilder();
        str.append("<!DOCTYPE html>\n<html>\n");

        // header containing styles and javascript functions
        str.append("<head>\n");
        str.append("<link type=\"text/css\" rel=\"stylesheet\" href=\"framing.css\">\n"); // style
        str.append("<script type=\"text/javascript\" src=\"framing.js\"></script>\n"); // script
        str.append("</head>\n"); // end head

        // start body
        str.append("<body>\n");
        str.append("<table>\n");
        str.append("<tbody>\n");

        Stack<SNode> stack = new Stack<SNode>();
        stack.add(globalTreeRoot);

        while (!stack.isEmpty()) {
            SNode node = stack.pop();

            ArrayList<RankingItem<SNode>> rankChildren = new ArrayList<RankingItem<SNode>>();
            for (SNode child : node.getChildren()) {
                rankChildren.add(new RankingItem<SNode>(child, child.getRegressionParameter()));
            }
            Collections.sort(rankChildren);
            for (RankingItem<SNode> rankChild : rankChildren) {
                stack.add(rankChild.getObject());
            }

            double[] nodeTopic = node.getTopic();
            String[] topWords = getTopWords(nodeTopic, numWords);

            if (node.getLevel() == 1) {
                str.append("<tr class=\"level").append(node.getLevel()).append("\">\n");
                str.append("<td>\n")
                        .append("[Topic ").append(node.getPathString())
                        .append("] ")
                        .append(" (")
                        .append(node.getNumChildren())
                        .append("; ").append(node.getNumCustomers())
                        .append("; ").append(node.getContent().getCountSum())
                        .append("; ").append(formatter.format(node.getRegressionParameter()))
                        .append(")");
                for (String topWord : topWords) {
                    str.append(" ").append(topWord);
                }
                str.append("</td>\n");
                str.append("</tr>\n");
            } else if (node.getLevel() == 2) {
                ArrayList<RankingItem<String>> rankSents = pathRankSentMap.get(node);
                if (rankSents == null || rankSents.size() < 10) {
                    continue;
                }
                Collections.sort(rankSents);

                // node info
                str.append("<tr class=\"level").append(node.getLevel()).append("\">\n");
                str.append("<td>\n")
                        .append("<a style=\"text-decoration:underline;color:blue;\" onclick=\"showme('")
                        .append(node.getPathString())
                        .append("');\" id=\"toggleDisplay\">")
                        .append("[Frame candidate ")
                        .append(node.getPathString())
                        .append("]</a>")
                        .append(" (").append(node.getNumCustomers())
                        .append("; ").append(node.getContent().getCountSum())
                        .append("; ").append(formatter.format(node.getRegressionParameter()))
                        .append(")");
                for (String topWord : topWords) {
                    str.append(" ").append(topWord);
                }
                str.append("</td>\n");
                str.append("</tr>\n");

                // sentences
                str.append("<tr class=\"level").append(L).append("\"")
                        .append(" id=\"").append(node.getPathString()).append("\"")
                        .append(" style=\"display:none;\"")
                        .append(">\n");
                str.append("<td>\n");

                for (int ii = 0; ii < Math.min(numSents, rankSents.size()); ii++) {
                    RankingItem<String> sent = rankSents.get(ii);
                    int d = Integer.parseInt(sent.getObject().split("-")[0]);
                    int s = Integer.parseInt(sent.getObject().split("-")[1]);
                    double score = sent.getPrimaryValue();

                    String debateId = docIds[d].substring(0, docIds[d].indexOf("_"));
                    str.append("<a href=\"")
                            .append("https://www.govtrack.us/data/us/112/cr/")
                            .append(debateId).append(".xml")
                            .append("\" ")
                            .append("target=\"_blank\">")
                            .append(docIds[d]).append("_").append(s)
                            .append("</a> ")
                            .append(" [").append(MiscUtils.formatDouble(score)).append("] ")
                            .append(rawSentences[d][s])
                            .append("<br/>\n");
                    str.append("</br>");
                }
                str.append("</td>\n</tr>\n");
            }
        }

        str.append("</tbody>\n");
        str.append("</table>\n");
        str.append("</body>\n");
        str.append("</html>");

        // output to file
        BufferedWriter writer = IOUtils.getBufferedWriter(htmlFile);
        writer.write(str.toString());
        writer.close();
    }

    public File getIterationPredictionFolder() {
        return new File(getSamplerFolderPath(), IterPredictionFolder);
    }

    public void testSampler(int[][][] newWords) {
        if (verbose) {
            logln("Test sampling ...");
        }
        File reportFolder = new File(getSamplerFolderPath(), ReportFolder);
        if (!reportFolder.exists()) {
            throw new RuntimeException("Report folder does not exist");
        }
        String[] filenames = reportFolder.list();

        File iterPredFolder = new File(getSamplerFolderPath(), IterPredictionFolder);
        IOUtils.createFolder(iterPredFolder);

        try {
            for (int i = 0; i < filenames.length; i++) {
                String filename = filenames[i];
                if (!filename.contains("zip")) {
                    continue;
                }

                File partialResultFile = new File(iterPredFolder, IOUtils.removeExtension(filename) + ".txt");
                sampleNewDocuments(
                        new File(reportFolder, filename),
                        newWords,
                        partialResultFile.getAbsolutePath());
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Exception while sampling during test time.");
        }
    }

    private void sampleNewDocuments(
            File stateFile,
            int[][][] newWords,
            String outputResultFile) throws Exception {
        if (verbose) {
            logln("\nPerform regression using model from " + stateFile);
        }

        try {
            inputModel(stateFile.getAbsolutePath());
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }

        words = newWords;
        responses = null; // for evaluation
        D = words.length;

        sentCount = 0;
        tokenCount = 0;
        docTokenCounts = new int[D];
        for (int d = 0; d < D; d++) {
            sentCount += words[d].length;
            for (int s = 0; s < words[d].length; s++) {
                tokenCount += words[d][s].length;
                docTokenCounts[d] += words[d][s].length;
            }
        }

        logln("--- V = " + V);
        logln("--- # documents = " + D); // number of groups
        logln("--- # sentences = " + sentCount);
        logln("--- # tokens = " + tokenCount);

        // initialize structure for test data
        initializeDataStructure();

        if (verbose) {
            logln("Initialized data structure");
            logln(printGlobalTreeSummary());
            logln(printLocalRestaurantSummary());
        }

        // initialize random assignments
        initializeRandomAssignmentsNewDocuments();

        updateDocumentTopicWeights();
        updateDocumentLexicalWeights();

        if (verbose) {
            logln("Initialized random assignments");
            logln(printGlobalTreeSummary());
            logln(printLocalRestaurantSummary());
        }

        if (debug) {
            validateAssignments("Initialized");
        }

        // iterate
        ArrayList<double[]> predResponsesList = new ArrayList<double[]>();
        for (iter = 0; iter < MAX_ITER; iter++) {
            for (int d = 0; d < D; d++) {
                for (int s = 0; s < words[d].length; s++) {
                    if (words[d].length > 1) // if this document has only 1 sentence, no sampling is needed
                    {
                        sampleTableForSentence(d, s, REMOVE, ADD, !OBSERVED, !EXTEND);
                    }

                    for (int n = 0; n < words[d][s].length; n++) {
                        sampleLevelForToken(d, s, n, REMOVE, ADD, !OBSERVED);
                    }
                }

                for (STable table : localRestaurants[d].getTables()) {
                    samplePathForTable(d, table, REMOVE, ADD, !OBSERVED, !EXTEND);
                }
            }

            if (verbose && iter % LAG == 0) {
                logln("--- iter = " + iter + " / " + MAX_ITER);
            }

            if (iter >= BURN_IN && iter % LAG == 0) {
                this.updateDocumentLexicalWeights();
                this.updateDocumentTopicWeights();

                double[] predResponses = getRegressionValues();
                predResponsesList.add(predResponses);
            }
        }

        // output result during test time 
        BufferedWriter writer = IOUtils.getBufferedWriter(outputResultFile);
        for (int d = 0; d < D; d++) {
            writer.write(Integer.toString(d));

            for (int ii = 0; ii < predResponsesList.size(); ii++) {
                writer.write("\t" + predResponsesList.get(ii)[d]);
            }
            writer.write("\n");
        }
        writer.close();
    }

    private void initializeRandomAssignmentsNewDocuments() {
        if (verbose) {
            logln("--- Initializing random assignments ...");
        }

        for (int d = 0; d < D; d++) {
            for (int s = 0; s < words[d].length; s++) {
                // create a new table for each sentence
                STable table = new STable(iter, s, null, d,
                        new TruncatedStickBreaking(L, hyperparams.get(GEM_MEAN),
                        hyperparams.get(GEM_SCALE)));
                localRestaurants[d].addTable(table);
                localRestaurants[d].addCustomerToTable(s, table.getIndex());
                c[d][s] = table;

                // initialize all tokens at the leaf node first
                for (int n = 0; n < words[d][s].length; n++) {
                    z[d][s][n] = L - 1;
                    table.incrementLevelCount(z[d][s][n]);
                    sentLevelCounts[d][s][z[d][s][n]]++;
                }
            }
        }

        for (int d = 0; d < D; d++) {
            for (STable table : localRestaurants[d].getTables()) {
                samplePathForTable(d, table, !REMOVE, ADD, !OBSERVED, !EXTEND);
            }
        }
    }

    class SNode extends TopicTreeNode<SNode, DirMult> {

        private final int born;
        private int numCustomers;
        private double regression;

        SNode(int iter, int index, int level,
                DirMult content,
                double regParam,
                SNode parent) {
            super(index, level, content, parent);
            this.born = iter;
            this.numCustomers = 0;
            this.regression = regParam;
        }

        public int getIterationCreated() {
            return this.born;
        }

        /**
         * Get the log probability of a set of observations given the topic at
         * this node.
         *
         * @param obs The set of observations
         */
        public double getLogProbability(SparseCount obs) {
            if (this.getTopic() == null) {
                return this.content.getLogLikelihood(obs.getObservations());
            } else {
                double val = 0.0;
                for (int o : obs.getIndices()) {
                    val += obs.getCount(o) * this.getLogProbability(o);
                }
                return val;
            }
        }

        double getLogJointProbability(double gamma) {
            ArrayList<Integer> numChildrenCusts = new ArrayList<Integer>();
            for (SNode child : this.getChildren()) {
                numChildrenCusts.add(child.getNumCustomers());
            }
            return SamplerUtils.getAssignmentJointLogProbability(numChildrenCusts, gamma);
        }

        public double getRegressionParameter() {
            return this.regression;
        }

        public void setRegressionParameter(double reg) {
            this.regression = reg;
        }

        public int getNumCustomers() {
            return this.numCustomers;
        }

        public void decrementNumCustomers() {
            this.numCustomers--;
        }

        public void incrementNumCustomers() {
            this.numCustomers++;
        }

        public void changeNumCustomers(int delta) {
            this.numCustomers += delta;
        }

        public boolean isEmpty() {
            return this.numCustomers == 0;
        }

        @Override
        public String toString() {
            StringBuilder str = new StringBuilder();
            str.append("[")
                    .append(getPathString())
                    .append(" (").append(born).append(")")
                    .append(" #ch = ").append(getNumChildren())
                    .append(", #c = ").append(getNumCustomers())
                    .append(", #o = ").append(getContent().getCountSum())
                    .append(", reg = ").append(MiscUtils.formatDouble(regression))
                    .append("]");
            return str.toString();
        }

        void validate(String msg) {
            int maxChildIndex = PSEUDO_NODE_INDEX;
            for (SNode child : this.getChildren()) {
                if (maxChildIndex < child.getIndex()) {
                    maxChildIndex = child.getIndex();
                }
            }

            for (int i = 0; i < maxChildIndex; i++) {
                if (!inactiveChildren.contains(i) && !isChild(i)) {
                    throw new RuntimeException(msg + ". Child inactive indices"
                            + " have not been updated. Node: " + this.toString()
                            + ". Index " + i + " is neither active nor inactive");
                }
            }
        }
    }

    class STable extends FullTable<Integer, SNode> {

        private final int born;
        private final int restIndex;
        private TruncatedStickBreaking levelDist;

        public STable(int iter, int index,
                SNode content, int restId,
                TruncatedStickBreaking levelDist) {
            super(index, content);
            this.born = iter;
            this.restIndex = restId;
            this.levelDist = levelDist;
        }

        public TruncatedStickBreaking getLevelDistribution() {
            return this.levelDist;
        }

        public void decrementLevelCount(int l) {
            this.levelDist.decrement(l);
        }

        public void incrementLevelCount(int l) {
            this.levelDist.increment(l);
        }

        public void changeLevelCount(int l, int delta) {
            this.levelDist.changeCount(l, delta);
        }

        public void decreaseLevelCounts(int[] ls) {
            for (int ll = 0; ll < ls.length; ll++) {
                this.levelDist.changeCount(ll, -ls[ll]);
            }
        }

        public void increaseLevelCounts(int[] ls) {
            for (int ll = 0; ll < ls.length; ll++) {
                this.levelDist.changeCount(ll, ls[ll]);
            }
        }

        public int getRestaurantIndex() {
            return this.restIndex;
        }

        public boolean containsCustomer(int c) {
            return this.customers.contains(c);
        }

        public int getIterationCreated() {
            return this.born;
        }

        public String getTableId() {
            return restIndex + ":" + index;
        }

        @Override
        public int hashCode() {
            String hashCodeStr = getTableId();
            return hashCodeStr.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if ((obj == null) || (this.getClass() != obj.getClass())) {
                return false;
            }
            STable r = (STable) (obj);

            return r.index == this.index
                    && r.restIndex == this.restIndex;
        }

        @Override
        public String toString() {
            StringBuilder str = new StringBuilder();
            str.append("[")
                    .append(getTableId())
                    .append(", ").append(born)
                    .append(", ").append(getNumCustomers())
                    .append("]")
                    .append(" >> ").append(getContent() == null ? "null" : getContent().toString());
            return str.toString();
        }
    }

    public static void main(String[] args) {
        run(args);
    }

    public static String getHelpString() {
        return "java -cp dist/segan.jar " + SHLDA.class.getName() + " -help";
    }

    public static void run(String[] args) {
        try {
            // create the command line parser
            parser = new BasicParser();

            // create the Options
            options = new Options();

            // directories
            addOption("output", "Output folder");
            addOption("dataset", "Dataset");
            addOption("data-folder", "Processed data folder");
            addOption("format-folder", "Folder holding formatted data");
            addOption("format-file", "Format file");

            // sampler configurations
            addOption("burnIn", "Burn-in");
            addOption("maxIter", "Maximum number of iterations");
            addOption("sampleLag", "Sample lag");
            addOption("report", "Report interval.");

            // hyperparameters
            addOption("T", "Lexical regression regularizer");
            addOption("num-topics", "Number of topics for initialization");
            addOption("num-frames", "Number of frames per-topic for initialization");
            addOption("gem-mean", "GEM mean. [0.5]");
            addOption("gem-scale", "GEM scale. [50]");
            addOption("betas", "Dirichlet hyperparameter for topic distributions."
                    + " [1, 0.5, 0.25] for a 3-level tree.");
            addOption("gammas", "DP hyperparameters. [1.0, 1.0] for a 3-level tree");
            addOption("mus", "Prior means for topic regression parameters."
                    + " [0.0, 0.0, 0.0] for a 3-level tree and standardized"
                    + " response variable.");
            addOption("sigmas", "Prior variances for topic regression parameters."
                    + " [0.0001, 0.5, 1.0] for a 3-level tree and stadardized"
                    + " response variable.");
            addOption("rho", "Prior variance for response variable. [1.0]");
            addOption("tau-mean", "Prior mean of lexical regression parameters. [0.0]");
            addOption("tau-scale", "Prior scale of lexical regression parameters. [1.0]");
            addOption("num-lex-items", "Number of non-zero lexical regression parameters."
                    + " Defaule: vocabulary size.");

            // cross validation
            addOption("cv-folder", "Cross validation folder");
            addOption("num-folds", "Number of folds");
            addOption("fold", "The cross-validation fold to run");
            addOption("run-mode", "Running mode");

            options.addOption("paramOpt", false, "Whether hyperparameter "
                    + "optimization using slice sampling is performed");
            options.addOption("z", false, "whether standardize (z-score normalization)");
            options.addOption("v", false, "verbose");
            options.addOption("d", false, "debug");
            options.addOption("help", false, "Help");

            cmd = parser.parse(options, args);
            if (cmd.hasOption("help")) {
                CLIUtils.printHelp(getHelpString(), options);
                return;
            }

            if (cmd.hasOption("cv-folder")) {
                runCrossValidation();
            } else {
                runModel();
            }
        } catch (Exception e) {
            e.printStackTrace();
            CLIUtils.printHelp(getHelpString(), options);
            System.exit(1);
        }
    }

    private static void runModel() throws Exception {
//        String datasetName = CLIUtils.getStringArgument(cmd, "dataset", "amazon-data");
//        String datasetFolder = CLIUtils.getStringArgument(cmd, "data-folder", "demo");
//        String resultFolder = CLIUtils.getStringArgument(cmd, "output", "demo/amazon-data/model");
//        String formatFolder = CLIUtils.getStringArgument(cmd, "format-folder", "format-response");
//        String formatFile = CLIUtils.getStringArgument(cmd, "format-file", datasetName);

//        String datasetName = CLIUtils.getStringArgument(cmd, "dataset", "112");
//        String datasetFolder = CLIUtils.getStringArgument(cmd, "data-folder",
//                "/Users/vietan/Dropbox/github/teaparty/data/govtrack");
//        String resultFolder = CLIUtils.getStringArgument(cmd, "output",
//                "/Users/vietan/Dropbox/github/teaparty/data/govtrack/112/format-teaparty-model");
//        String formatFolder = CLIUtils.getStringArgument(cmd, "format-folder", "format-teaparty");
//        String formatFile = CLIUtils.getStringArgument(cmd, "format-file", datasetName);

        String datasetName = CLIUtils.getStringArgument(cmd, "dataset", "112");
        String datasetFolder = CLIUtils.getStringArgument(cmd, "data-folder",
                "L:/Datasets/teaparty/govtrack");
        String resultFolder = CLIUtils.getStringArgument(cmd, "output",
                "L:/Datasets/teaparty/govtrack/112/format-teaparty-model");
        String formatFolder = CLIUtils.getStringArgument(cmd, "format-folder", "format-teaparty");
        String formatFile = CLIUtils.getStringArgument(cmd, "format-file", datasetName);

        int numTopWords = CLIUtils.getIntegerArgument(cmd, "numTopwords", 20);

        int burnIn = CLIUtils.getIntegerArgument(cmd, "burnIn", 250);
        int maxIters = CLIUtils.getIntegerArgument(cmd, "maxIter", 500);
        int sampleLag = CLIUtils.getIntegerArgument(cmd, "sampleLag", 25);
        int repInterval = CLIUtils.getIntegerArgument(cmd, "report", 1);
        double T = CLIUtils.getDoubleArgument(cmd, "T", 500);

        int numTopics = CLIUtils.getIntegerArgument(cmd, "num-topics", 10);
        int numFrames = CLIUtils.getIntegerArgument(cmd, "num-frames", 3);

        boolean paramOpt = cmd.hasOption("paramOpt");
        boolean verbose = cmd.hasOption("v");
        boolean debug = cmd.hasOption("d");
        verbose = true;
        debug = true;
        InitialState initState = InitialState.PRESET;

        if (verbose) {
            System.out.println("\nLoading formatted data ...");
        }
        ResponseTextDataset data = new ResponseTextDataset(datasetName, datasetFolder);
        data.setFormatFilename(formatFile);
        data.loadFormattedData(new File(data.getDatasetFolderPath(), formatFolder));
        data.prepareTopicCoherence(numTopWords);

        int V = data.getWordVocab().size();
        int L = CLIUtils.getIntegerArgument(cmd, "tree-height", 3);
        double gem_mean = CLIUtils.getDoubleArgument(cmd, "gem-mean", 0.3);
        double gem_scale = CLIUtils.getDoubleArgument(cmd, "gem-scale", 50);

        double[] defaultBetas = new double[L];
        defaultBetas[0] = 1;
        for (int i = 1; i < L; i++) {
            defaultBetas[i] = 1.0 / (i + 1);
        }
        double[] betas = CLIUtils.getDoubleArrayArgument(cmd, "betas", defaultBetas, ",");

        double[] defaultGammas = new double[L - 1];
        for (int i = 0; i < defaultGammas.length; i++) {
            defaultGammas[i] = 1.0 / (i + 1);
        }

        double[] gammas = CLIUtils.getDoubleArrayArgument(cmd, "gammas", defaultGammas, ",");

        if (cmd.hasOption("z")) {
            data.zNormalize();
        }
        else {
            System.out.println("--- [WARNING] Running with unnormalized response "
                    + "variables. Use option -z to perform z-normalization.");
        }

        double meanResponse = StatisticsUtils.mean(data.getResponses());
        double[] defaultMus = new double[L];
        for (int i = 0; i < L; i++) {
            defaultMus[i] = meanResponse;
        }
        double[] mus = CLIUtils.getDoubleArrayArgument(cmd, "mus", defaultMus, ",");

        double[] defaultSigmas = new double[L];
        defaultSigmas[0] = 0.0001; // root node
        for (int l = 1; l < L; l++) {
            defaultSigmas[l] = 0.5 * l;
        }
        double[] sigmas = CLIUtils.getDoubleArrayArgument(cmd, "sigmas", defaultSigmas, ",");
        double tau_mean = CLIUtils.getDoubleArgument(cmd, "tau-mean", 0.0);
        double tau_scale = CLIUtils.getDoubleArgument(cmd, "tau-scale", 1.0);
        double alpha = CLIUtils.getDoubleArgument(cmd, "alpha", 1.0);
        double rho = CLIUtils.getDoubleArgument(cmd, "rho", 1.0);

        if (verbose) {
            System.out.println("\nRunning sampler ...");
        }

        SHLDA sampler = new SHLDA();
        sampler.setVerbose(verbose);
        sampler.setDebug(debug);
        sampler.setLog(true);
        sampler.setReport(true);
        sampler.setWordVocab(data.getWordVocab());

        sampler.setNumInitTopics(numTopics);
        sampler.setNumInitFrames(numFrames);

        sampler.train(data);

        sampler.configure(resultFolder,
                V, L, T,
                alpha,
                rho,
                gem_mean, gem_scale,
                tau_mean, tau_scale,
                betas, gammas,
                mus, sigmas,
                initState,
                PathAssumption.MAXIMAL,
                paramOpt,
                burnIn, maxIters, sampleLag, repInterval);

        File samplerFolder = new File(resultFolder, sampler.getSamplerFolder());
        IOUtils.createFolder(samplerFolder);
//        sampler.initialize();
//        sampler.iterate();

        sampler.inputFinalState();
        sampler.outputTopicTopWords(new File(samplerFolder, TopWordFile), numTopWords);
        sampler.outputSentences(new File(samplerFolder, "sentences.txt"), data.getRawSentences(), 15);
        sampler.outputHTML(new File(samplerFolder, "sentences.html"),
                data.getDocIds(),
                data.getRawSentences(),
                15,
                15);

//        sampler.outputTopicCoherence(new File(shldaFolder, TopicCoherenceFile), topicCoherence);
//        sampler.outputLexicalWeights(new File(samplerFolder, "lexical-reg-params.txt"));
//        sampler.outputDocPathAssignments(new File(samplerFolder, "doc-topic.txt"));
//        sampler.outputTopicWordDistributions(new File(samplerFolder, "topic-word.txt"));
    }

    private static void runCrossValidation() throws Exception {
        String cvFolder = cmd.getOptionValue("cv-folder");
        int numFolds = Integer.parseInt(cmd.getOptionValue("num-folds"));
        String runMode = cmd.getOptionValue("run-mode");
        String resultFolder = cmd.getOptionValue("output");
        int numTopWords = CLIUtils.getIntegerArgument(cmd, "numTopwords", 20);
        int foldIndex = -1;
        if (cmd.hasOption("fold")) {
            foldIndex = Integer.parseInt(cmd.getOptionValue("fold"));
        }

        // sampling
        int burnIn = CLIUtils.getIntegerArgument(cmd, "burnIn", 250);
        int maxIters = CLIUtils.getIntegerArgument(cmd, "maxIter", 500);
        int sampleLag = CLIUtils.getIntegerArgument(cmd, "sampleLag", 50);
        int repInterval = CLIUtils.getIntegerArgument(cmd, "report", 1);

        // initialization
        int numTopics = CLIUtils.getIntegerArgument(cmd, "num-topics", 10);
        int numFrames = CLIUtils.getIntegerArgument(cmd, "num-frames", 3);

        // parameters
        int L = CLIUtils.getIntegerArgument(cmd, "tree-height", 3);
        double T = CLIUtils.getDoubleArgument(cmd, "T", 1000);
        double gem_mean = CLIUtils.getDoubleArgument(cmd, "gem-mean", 0.3);
        double gem_scale = CLIUtils.getDoubleArgument(cmd, "gem-scale", 50);

        double[] defaultBetas = new double[L];
        defaultBetas[0] = 1;
        for (int i = 1; i < L; i++) {
            defaultBetas[i] = 1.0 / (i + 1);
        }
        double[] betas = CLIUtils.getDoubleArrayArgument(cmd, "betas", defaultBetas, ",");

        double[] defaultGammas = new double[L - 1];
        for (int i = 0; i < defaultGammas.length; i++) {
            defaultGammas[i] = 1.0 / (i + 1);
        }
        double[] gammas = CLIUtils.getDoubleArrayArgument(cmd, "gammas", defaultGammas, ",");

        boolean paramOpt = cmd.hasOption("paramOpt");
        boolean verbose = cmd.hasOption("v");
        boolean debug = cmd.hasOption("d");
        InitialState initState = InitialState.PRESET;

        for (int ii = 0; ii < numFolds; ii++) {
            if (foldIndex != -1 && ii != foldIndex) {
                continue;
            }
            if (verbose) {
                System.out.println("\nRunning fold " + foldIndex);
            }

            Fold fold = new Fold(ii, cvFolder);
            File foldFolder = new File(resultFolder, fold.getFoldName());
            ResponseTextDataset[] foldData = ResponseTextDataset.loadCrossValidationFold(fold);
            ResponseTextDataset trainData = foldData[Fold.TRAIN];
            ResponseTextDataset devData = foldData[Fold.DEV];
            ResponseTextDataset testData = foldData[Fold.TEST];

            if (cmd.hasOption("z")) {
                ResponseTextDataset.zNormalize(trainData, devData, testData);
            }

            if (verbose) {
                System.out.println("Fold " + fold.getFoldName());
                System.out.println("--- training: " + trainData.toString());
                System.out.println("--- development: " + devData.toString());
                System.out.println("--- test: " + testData.toString());
                System.out.println();
            }

            int V = trainData.getWordVocab().size();

            if (cmd.hasOption("z")) {
                ResponseTextDataset.zNormalize(trainData, devData, testData);
            }

            double meanResponse = StatisticsUtils.mean(trainData.getResponses());
            double[] defaultMus = new double[L];
            for (int i = 0; i < L; i++) {
                defaultMus[i] = meanResponse;
            }
            double[] mus = CLIUtils.getDoubleArrayArgument(cmd, "mus", defaultMus, ",");

            double[] defaultSigmas = new double[L];
            defaultSigmas[0] = 0.0001; // root node
            for (int l = 1; l < L; l++) {
                defaultSigmas[l] = 0.5 * l;
            }
            double[] sigmas = CLIUtils.getDoubleArrayArgument(cmd, "sigmas", defaultSigmas, ",");
            double tau_mean = CLIUtils.getDoubleArgument(cmd, "tau-mean", 0.0);
            double tau_scale = CLIUtils.getDoubleArgument(cmd, "tau-scale", 1.0);
            double alpha = CLIUtils.getDoubleArgument(cmd, "alpha", 1.0);
            double rho = CLIUtils.getDoubleArgument(cmd, "rho", 1.0);

            SHLDA sampler = new SHLDA();
            sampler.setVerbose(verbose);
            sampler.setDebug(debug);
            sampler.setLog(true);
            sampler.setReport(true);
            sampler.setWordVocab(trainData.getWordVocab());

            sampler.setNumInitTopics(numTopics);
            sampler.setNumInitFrames(numFrames);

            // train
            sampler.train(trainData);
            sampler.configure(foldFolder.getAbsolutePath(),
                    V, L, T,
                    alpha,
                    rho,
                    gem_mean, gem_scale,
                    tau_mean, tau_scale,
                    betas, gammas,
                    mus, sigmas,
                    initState,
                    PathAssumption.MAXIMAL,
                    paramOpt,
                    burnIn, maxIters, sampleLag, repInterval);

            File samplerFolder = new File(foldFolder, sampler.getSamplerFolder());
            File iterPredFolder = sampler.getIterationPredictionFolder();
            File teResultFolder = new File(samplerFolder, "te-results");
            IOUtils.createFolder(samplerFolder);

            if (runMode.equals("train")) {
                sampler.sample();
                sampler.outputTopicTopWords(new File(samplerFolder, TopWordFile), numTopWords);
                sampler.outputSentences(new File(samplerFolder, "sentences.txt"), trainData.getRawSentences(), 10);
            } else if (runMode.equals("test")) {
                IOUtils.createFolder(teResultFolder);
                sampler.test(testData);
                GibbsRegressorUtils.evaluate(iterPredFolder, teResultFolder, testData.getResponses());
            } else if (runMode.equals("train-test")) {
                // train
                sampler.sample();
                sampler.outputTopicTopWords(new File(samplerFolder, TopWordFile), numTopWords);
                sampler.outputSentences(new File(samplerFolder, "sentences.txt"), trainData.getRawSentences(), 10);
//                sampler.outputDocTopicDistributions(new File(samplerFolder, "tr-doc-topic.txt"));
//                sampler.outputTopicWordDistributions(new File(samplerFolder, "topic-word.txt"));
//                sampler.outputTopicRegressionParameters(new File(samplerFolder, "topic-reg-params.txt"));

                // test
                IOUtils.createFolder(teResultFolder);
                sampler.test(testData);
                GibbsRegressorUtils.evaluate(iterPredFolder, teResultFolder, testData.getResponses());
//                sampler.outputDocTopicDistributions(new File(samplerFolder, "te-doc-topic.txt"));
            } else if (runMode.equals("hack")) {
                sampler.inputFinalState();
                sampler.outputTopicTopWords(new File(samplerFolder, TopWordFile), numTopWords);
                sampler.outputSentences(new File(samplerFolder, "sentences.txt"), trainData.getRawSentences(), 10);

                int[][] teWords = testData.getWords();

                int D = teWords.length;
                double[][] desginMatrix = new double[D][V];
                for (int d = 0; d < D; d++) {
                    for (int n = 0; n < teWords[d].length; n++) {
                        desginMatrix[d][teWords[d][n]]++;
                    }
                    for (int v = 0; v < V; v++) {
                        desginMatrix[d][v] /= teWords[d].length;
                    }
                }

                double[] predictions = new double[D];
                for (int d = 0; d < D; d++) {
                    double predVal = 0.0;
                    for (int v = 0; v < V; v++) {
                        if (sampler.lexicalWeights.get(v) != null) {
                            predVal += desginMatrix[d][v] * sampler.lexicalWeights.get(v);
                        }
                    }

                    predictions[d] = predVal;
                }

                RegressionEvaluation eval = new RegressionEvaluation(
                        testData.getResponses(), predictions);
                eval.computeCorrelationCoefficient();
                eval.computeMeanSquareError();
                eval.computeRSquared();

                ArrayList<Measurement> measurements = eval.getMeasurements();
                for (Measurement measurement : measurements) {
                    System.out.println(measurement.getName()
                            + "\t" + measurement.getValue()
                            + "\n");
                }

            } else {
                throw new RuntimeException("Run mode " + runMode + " is not supported");
            }
        }
    }
}