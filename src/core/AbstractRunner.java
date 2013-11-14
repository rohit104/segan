package core;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;

/**
 *
 * @author vietan
 */
public class AbstractRunner {

    public static final String TopWordFile = AbstractExperiment.TopWordFile;
    public static final String TopicCoherenceFile = AbstractExperiment.TopicCoherenceFile;
    protected static CommandLineParser parser;
    protected static Options options;
    protected static CommandLine cmd;
    protected static boolean verbose = true;
    protected static boolean debug = true;

    protected static void addOption(String optName, String optDesc) {
        options.addOption(OptionBuilder.withLongOpt(optName)
                .withDescription(optDesc)
                .hasArg()
                .withArgName(optName)
                .create());
    }

    public static void setVerbose(boolean v) {
        verbose = v;
    }

    public static void setDebug(boolean d) {
        debug = d;
    }
}