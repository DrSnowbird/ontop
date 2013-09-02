/*
 * Copyright (C) 2009-2013, Free University of Bozen Bolzano
 * This source code is available under the terms of the Affero General Public
 * License v3.
 * 
 * Please see LICENSE.txt for full license terms, including the availability of
 * proprietary exceptions.
 */
import info.aduna.io.FileUtil;
import info.aduna.io.ZipUtil;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.util.jar.JarFile;

import junit.framework.TestResult;
import junit.framework.TestSuite;

import org.openrdf.OpenRDFUtil;
import org.openrdf.model.Resource;
import org.openrdf.model.ValueFactory;
import org.openrdf.query.BindingSet;
import org.openrdf.query.QueryLanguage;
import org.openrdf.query.TupleQueryResult;
import org.openrdf.repository.Repository;
import org.openrdf.repository.RepositoryConnection;
import org.openrdf.repository.RepositoryException;
import org.openrdf.repository.sail.SailRepository;
import org.openrdf.repository.util.RDFInserter;
import org.openrdf.rio.ParserConfig;
import org.openrdf.rio.RDFFormat;
import org.openrdf.rio.RDFHandlerException;
import org.openrdf.rio.RDFParseException;
import org.openrdf.rio.RDFParser;
import org.openrdf.rio.Rio;
import org.openrdf.rio.helpers.BasicParserSettings;
import org.openrdf.sail.memory.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RDB2RDFManifestUtils {

	static final Logger logger = LoggerFactory.getLogger(RDB2RDFManifestUtils.class);

	public static TestSuite suite(RDB2RDFScenarioParent.Factory factory) throws Exception {
		final String manifestFile;
		final File tmpDir;

		URL url = RDB2RDFManifestUtils.class.getResource(factory.getMainManifestFile());
		
		if ("jar".equals(url.getProtocol())) {
			// Extract manifest files to a temporary directory
			try {
				tmpDir = FileUtil.createTempDir("scenario-evaluation");

				JarURLConnection con = (JarURLConnection) url.openConnection();
				JarFile jar = con.getJarFile();

				ZipUtil.extract(jar, tmpDir);

				File localFile = new File(tmpDir, con.getEntryName());
				manifestFile = localFile.toURI().toURL().toString();
			} catch (IOException e) {
				throw new AssertionError(e);
			}
		} else {
			manifestFile = url.toString();
			tmpDir = null;
		}
		
		TestSuite suite = new TestSuite(factory.getClass().getName()) {
			@Override
			public void run(TestResult result) {
				try {
					super.run(result);
				} finally {
					if (tmpDir != null) {
						try {
							FileUtil.deleteDir(tmpDir);
						} catch (IOException e) {
							System.err
									.println("Unable to clean up temporary directory '"
											+ tmpDir + "': " + e.getMessage());
						}
					}
				}
			}
		};

		Repository manifestRep = new SailRepository(new MemoryStore());
		manifestRep.initialize();
		RepositoryConnection con = manifestRep.getConnection();

		addTurtle(con, url, url.toString());

		String query = "SELECT DISTINCT manifestFile FROM {x} rdf:first {manifestFile} "
				+ "USING NAMESPACE mf = <http://obda.org/quest/tests/test-manifest#>, "
				+ "  qt = <http://obda.org/quest/tests/test-query#>";

		TupleQueryResult manifestResults = con.prepareTupleQuery(
				QueryLanguage.SERQL, query, manifestFile).evaluate();

		while (manifestResults.hasNext()) {
			BindingSet bindingSet = manifestResults.next();
			String subManifestFile = bindingSet.getValue("manifestFile")
					.toString();
			suite.addTest(RDB2RDFScenarioParent.suite(subManifestFile, factory));
		}

		manifestResults.close();
		con.close();
		manifestRep.shutDown();

		logger.info("Created aggregated test suite with "
				+ suite.countTestCases() + " test cases.");
		return suite;
	}

	static void addTurtle(RepositoryConnection con, URL url, String baseURI,
			Resource... contexts) throws IOException, RepositoryException,
			RDFParseException {
		if (baseURI == null) {
			baseURI = url.toExternalForm();
		}
		InputStream in = url.openStream();

		try {
			OpenRDFUtil.verifyContextNotNull(contexts);
			final ValueFactory vf = con.getRepository().getValueFactory();
			RDFParser rdfParser = Rio.createParser(RDFFormat.TURTLE, vf);
			ParserConfig config = rdfParser.getParserConfig();
			// To emulate DatatypeHandling.IGNORE 
			config.addNonFatalError(BasicParserSettings.FAIL_ON_UNKNOWN_DATATYPES);
			config.addNonFatalError(BasicParserSettings.VERIFY_DATATYPE_VALUES);
			config.addNonFatalError(BasicParserSettings.NORMALIZE_DATATYPE_VALUES);
//			rdfParser.setVerifyData(false);
//			rdfParser.setStopAtFirstError(true);
//			rdfParser.setDatatypeHandling(RDFParser.DatatypeHandling.IGNORE);

			RDFInserter rdfInserter = new RDFInserter(con);
			rdfInserter.enforceContext(contexts);
			rdfParser.setRDFHandler(rdfInserter);

			con.begin();

			try {
				rdfParser.parse(in, baseURI);
			} catch (RDFHandlerException e) {
					con.rollback();
				// RDFInserter only throws wrapped RepositoryExceptions
				throw (new RepositoryException(e.getCause()));
			} catch (RuntimeException e) {
					con.rollback();
				throw e;
			}
		} finally {
			in.close();
		}
	}
}