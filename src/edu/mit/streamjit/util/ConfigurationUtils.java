package edu.mit.streamjit.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

import edu.mit.streamjit.impl.common.Configuration;

/**
 * {@link ConfigurationUtils} contains common utility methods those deal with
 * {@link Configuration}.
 * 
 * @author Sumanan sumanan@mit.edu
 * @since May 10, 2013
 *
 */
public class ConfigurationUtils {

	/**
	 * @param cfgFilePath
	 *            path of the configuration file that need to be read.
	 * @return {@link Configuration} object if valid file exists. Otherwise
	 *         returns null.
	 */
	public static Configuration readConfiguration(String cfgFilePath) {
		try {
			BufferedReader reader = new BufferedReader(new FileReader(
					cfgFilePath));
			String json = reader.readLine();
			reader.close();
			return Configuration.fromJson(json);
		} catch (IOException ex) {
			System.err.println(String
					.format("File reader error. No %s configuration file.",
							cfgFilePath));
		} catch (Exception ex) {
			System.err.println(String.format(
					"File %s is not a configuration file.", cfgFilePath));
		}
		return null;
	}

	/**
	 * Saves the config into configurations/appName directory. output _.cfg file
	 * will be named as namePrefixappName.cfg.
	 * 
	 * @param config
	 *            {@link Configuration} that need to be saved.
	 * @param namePrefix
	 *            prefix to add to the out put file name.
	 * @param appName
	 *            name of the streamJit app. output _.cfg file will be named as
	 *            namePrefixappName.cfg.
	 */
	public static void saveConfg(Configuration config, String namePrefix,
			String appName) {
		String json = config.toJson();
		saveConfg(json, namePrefix, appName);
	}

	/**
	 * Saves the configJson into configurations/appName directory. output _.cfg
	 * file will be named as namePrefixappName.cfg.
	 * 
	 * @param configJson
	 *            Json representation of the {@link Configuration} that need to
	 *            be saved.
	 * @param namePrefix
	 *            prefix to add to the out put file name.
	 * @param appName
	 *            name of the streamJit app. output _.cfg file will be named as
	 *            namePrefixappName.cfg.
	 */
	public static void saveConfg(String configJson, String namePrefix,
			String appName) {
		try {

			File dir = new File(String.format("configurations%s%s",
					File.separator, appName));
			if (!dir.exists())
				if (!dir.mkdirs()) {
					System.err.println("Make directory failed");
					return;
				}

			File file = new File(dir, String.format("%s%s.cfg", namePrefix,
					appName));
			FileWriter writer = new FileWriter(file, false);
			writer.write(configJson);
			writer.flush();
			writer.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}
