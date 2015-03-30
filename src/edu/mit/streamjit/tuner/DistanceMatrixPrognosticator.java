package edu.mit.streamjit.tuner;

import java.io.IOException;
import java.io.OutputStreamWriter;

import edu.mit.streamjit.impl.common.Configuration;
import edu.mit.streamjit.impl.distributed.common.Utils;
import edu.mit.streamjit.tuner.ConfigurationAnalyzer.FullParameterSummary;
import edu.mit.streamjit.tuner.ConfigurationAnalyzer.ParamType;

/**
 * Calculates a weighted distance between the current configuration and the
 * previous configuration and decides whether to accept or reject the current
 * configuration.
 * 
 * @author sumanan
 * @since 23 Mar, 2015
 */
public class DistanceMatrixPrognosticator implements
		ConfigurationPrognosticator {

	FullParameterSummary fullParameterSummary;
	private final OutputStreamWriter writer;

	Configuration prevConfig = null;
	Configuration curConfig = null;
	Configuration bestConfig = null;

	double prevConfigTime = -1;
	double curConfigTime = -1;
	double bestConfigTime = Integer.MAX_VALUE;

	ComparisionSummary prevCurSummary = null;
	ComparisionSummary bestCurSummary = null;

	public DistanceMatrixPrognosticator(String appName) {
		this(Utils.fileWriter(appName, "DistanceMatrix.txt"));
	}

	public DistanceMatrixPrognosticator(OutputStreamWriter osWriter) {
		this.writer = osWriter;
		writeHeader(writer);
	}

	@Override
	public boolean prognosticate(Configuration config) {
		if (config == null)
			throw new IllegalArgumentException("Null Configuration");
		if (fullParameterSummary == null) {
			fullParameterSummary = new FullParameterSummary(config);
			curConfig = config;
			prevConfig = config;
			bestConfig = config;
			return true;
		}
		prevConfig = curConfig;
		prevConfigTime = curConfigTime;
		curConfig = config;

		// prevCurSummary = ComparisionSummary.compare(prevConfig, curConfig,
		// fullParameterSummary);
		bestCurSummary = ComparisionSummary.compare(bestConfig, curConfig,
				fullParameterSummary);
		return true;
	}

	private static void writeHeader(OutputStreamWriter writer) {
		try {
			writer.write(String.format("%.7s", "cfgID"));
			writer.write("\t\t");
			writer.write(String.format("%.7s", "distance"));
			writer.write("\t\t");
			writer.write(String.format("%.7s", "weiDist"));
			writer.write("\t\t");
			writer.write(String.format("%.7s", "normDist"));
			writer.write("\t\t");
			writer.write(String.format("%.7s", "WeiNormDist"));
			writer.write("\t\t");
			writer.write(String.format("%.7s", "Mul"));
			writer.write("\t\t");
			writer.write(String.format("%.7s", "Unroll"));
			writer.write("\t\t");
			writer.write(String.format("%.7s", "Alocation"));
			writer.write("\t\t");
			writer.write(String.format("%.7s", "InternStorege"));
			writer.write("\t\t");
			writer.write(String.format("%.7s", "Partition"));
			writer.write("\t\t");
			writer.write(String.format("%.7s", "Unbox"));
			writer.write("\t\t");
			writer.write(String.format("%.7s", "Removal"));
			writer.write("\t\t");
			writer.write(String.format("%.7s", "Fusion"));
			writer.write("\t\t");
			writer.write(String.format("%.7s", "A/R")); // Accepted or Rejected.
			writer.write("\t\t");
			writer.write(String.format("%.7s", "t1"));
			writer.write("\t\t");
			writer.write(String.format("%.7s", "t2"));
			writer.write("\t\t");
			writer.write(String.format("%.7s", "t1-t2"));
			// writer.write("\t\t");
			writer.flush();
		} catch (IOException e) {

		}
	}

	private static void writeSummary(OutputStreamWriter osWriter,
			ComparisionSummary sum, double t1, double t2) {
		if (sum == null)
			return;
		try {
			osWriter.write(String.format("%6s\t\t", sum.firstCfg));
			osWriter.write(String.format("%.2f\t\t", sum.distance()));
			osWriter.write(String.format("%.2f\t\t", sum.weightedDistance()));
			osWriter.write(String.format("%.2f\t\t", sum.normalizedDistance()));
			osWriter.write(String.format("%.2f\t\t",
					sum.weightedNormalizedDistance()));
			osWriter.write(String.format("%.5f\t\t",
					sum.distance(ParamType.MULTIPLIER)));
			osWriter.write(String.format("%.2f\t\t",
					sum.distance(ParamType.UNROLL_CORE)));
			osWriter.write(String.format("%.2f\t\t",
					sum.distance(ParamType.ALLOCATION_STRATEGY)));
			osWriter.write(String.format("%.2f\t\t",
					sum.distance(ParamType.INTERNAL_STORAGE_STRATEGY)));
			osWriter.write(String.format("%.2f\t\t",
					sum.distance(ParamType.PARTITION)));
			osWriter.write(String.format("%.2f\t\t",
					sum.distance(ParamType.UNBOXING_STRATEGY)));
			osWriter.write(String.format("%.2f\t\t",
					sum.distance(ParamType.REMOVAL_STRATEGY)));
			osWriter.write(String.format("%.2f\t\t",
					sum.distance(ParamType.FUSION_STRATEGY)));
			osWriter.write(String.format("%.2f\t\t", t1));
			osWriter.write(String.format("%.2f\t\t", t2));
			osWriter.write(String.format("%.2f\n", t1 - t2));
			osWriter.flush();
		} catch (IOException e) {

		}
	}

	@Override
	public void time(double time) {
		curConfigTime = time;
		// writeSummary(writer, prevCurSummary, prevConfigTime, curConfigTime);
		writeSummary(writer, bestCurSummary, bestConfigTime, curConfigTime);
		if (time > 0 && bestConfigTime > time) {
			bestConfig = curConfig;
			bestConfigTime = time;
		}
	}
}
