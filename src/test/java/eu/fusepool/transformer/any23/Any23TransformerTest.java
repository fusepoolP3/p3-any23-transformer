package eu.fusepool.transformer.any23;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.charset.Charset;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import javax.activation.MimeType;

import org.apache.clerezza.rdf.core.Graph;
import org.apache.clerezza.rdf.core.Language;
import org.apache.clerezza.rdf.core.Literal;
import org.apache.clerezza.rdf.core.MGraph;
import org.apache.clerezza.rdf.core.NonLiteral;
import org.apache.clerezza.rdf.core.Resource;
import org.apache.clerezza.rdf.core.Triple;
import org.apache.clerezza.rdf.core.UriRef;
import org.apache.clerezza.rdf.core.impl.PlainLiteralImpl;
import org.apache.clerezza.rdf.core.impl.SimpleMGraph;
import org.apache.clerezza.rdf.core.impl.TripleImpl;
import org.apache.clerezza.rdf.core.serializedform.Parser;
import org.apache.clerezza.rdf.core.serializedform.SupportedFormat;
import org.apache.clerezza.rdf.ontologies.RDF;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.LineIterator;
import org.apache.http.HttpStatus;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jayway.restassured.RestAssured;
import com.jayway.restassured.response.Response;
import com.jayway.restassured.response.ResponseBodyData;

import eu.fusepool.p3.transformer.server.TransformerServer;
import eu.fusepool.p3.vocab.TRANSFORMER;

public class Any23TransformerTest {
	

	private static final int MAX_TIMEOUT = 10000;
	private static final int MIN_TIMEOUT = 5000;
	private static final int MAX_RETRY = 10;
	private static final int RETRY_WAIT = MIN_TIMEOUT/MAX_RETRY;

	private static final UriRef SINDICE_ROW = new UriRef("http://vocab.sindice.net/csv/Row");

	private static final Logger log = LoggerFactory.getLogger(Any23TransformerTest.class);

	private static final String CSV_CONTENT_FILE = "test.csv";
	private static final String HTML_RDFA_CONTENT_FILE = "rdfa11.html";
	private static final String HTML_MICROFORMAT_CONTENT_FILE = "hcard.html";
	private static final String HTML_MICRODATA_CONTENT_FILE = "schemaorg.html";
	private static final String RDF_XML_CONTENT_FILE = "dcterms.rdf";

	private static final Set<String> EXPECTED_SUPPORTED_INPUT_FORMATS = new HashSet<String>();
	private static final Set<String> EXPECTED_SUPPORTED_OUTPUT_FORMATS = new HashSet<String>();

	static {
		for(MimeType mime : Any23Transformer.INPUT_FORMATS){
			EXPECTED_SUPPORTED_INPUT_FORMATS.add(mime.toString());
		}
		for(MimeType mime : Any23Transformer.OUTPUT_FORMATS){
			EXPECTED_SUPPORTED_OUTPUT_FORMATS.add(mime.toString());
		}
	}

	private static String BASE_URI;
	private static UriRef BASE_URI_REF;
	private static byte[] CSV_CONTENT;
	private static int CSV_LINE_COUNT;

	private static byte[] HTML_RDFA_CONTENT;
	
	private static byte[] HTML_MICROFORMAT_CONTENT;
	private static byte[] HTML_MICRODATA_CONTENT;

	private static byte[] RDF_XML_CONTENT;
	private static Graph RDF_XML_CONTENT_GRAPH;
	
	private static final Parser parser = Parser.getInstance();

	@BeforeClass
	public static void setUp() throws Exception {
		//init the transformer
		final int port = findFreePort();
		BASE_URI = "http://localhost:" + port + "/";
		BASE_URI_REF = new UriRef(BASE_URI);
		TransformerServer server = new TransformerServer(port);
		server.start(new Any23Transformer());
		
		//init the CSV content test data
		ClassLoader cl = Any23TransformerTest.class.getClassLoader();
		InputStream in = cl.getResourceAsStream(CSV_CONTENT_FILE);
		assertNotNull(in);
		StringBuilder sb = new StringBuilder();
		LineIterator li = IOUtils.lineIterator(in, "UTF-8");
		while(li.hasNext()){
			if(sb.length() != 0){
				CSV_LINE_COUNT++;
				sb.append('\n');
			} //else do not add initial line break and also do not count the first line
			sb.append(li.nextLine());
		}
		CSV_CONTENT = sb.toString().getBytes(Charset.forName("UTF-8"));
		IOUtils.closeQuietly(in);
		assertNotNull(CSV_CONTENT);
		
		//init RDFa content
		HTML_RDFA_CONTENT = readContent(HTML_RDFA_CONTENT_FILE);
		//init Microformat content
		HTML_MICROFORMAT_CONTENT = readContent(HTML_MICROFORMAT_CONTENT_FILE);
		HTML_MICRODATA_CONTENT = readContent(HTML_MICRODATA_CONTENT_FILE);
		//init RDF/XML content
		RDF_XML_CONTENT = readContent(RDF_XML_CONTENT_FILE);
		RDF_XML_CONTENT_GRAPH = parser.parse(
				cl.getResourceAsStream(RDF_XML_CONTENT_FILE), SupportedFormat.RDF_XML);
	}

	private static byte[] readContent(String file) throws IOException {
		InputStream in = Any23TransformerTest.class.getClassLoader().getResourceAsStream(file);
		assertNotNull("Test file "+file+" not found via classpath!",in);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			IOUtils.copy(in, out);
			return out.toByteArray();
		} finally {
			IOUtils.closeQuietly(in);
			IOUtils.closeQuietly(out);
		}
	}

	@Test
	public void turtleOnGet() {
		String accept = "text/turtle";
		Response response = RestAssured.given().header("Accept", accept)
				.expect().statusCode(HttpStatus.SC_OK)
				.header("Content-Type", accept).when().get(BASE_URI);
		
		Graph graph = parser.parse(
				response.getBody().asInputStream(), 
				response.getContentType());
		//Assert supported INPUT and OUTPUT formats
		Set<String> expected = new HashSet<String>(EXPECTED_SUPPORTED_INPUT_FORMATS);
		Iterator<Triple> it = graph.filter(BASE_URI_REF, TRANSFORMER.supportedInputFormat, null);
		while(it.hasNext()){
			Resource r = it.next().getObject();
			assertTrue(r instanceof Literal);
			assertTrue(expected.remove(((Literal)r).getLexicalForm()));
		}
		assertTrue(expected.isEmpty());
		
		expected = new HashSet<String>(EXPECTED_SUPPORTED_OUTPUT_FORMATS);
		it = graph.filter(BASE_URI_REF, TRANSFORMER.supportedOutputFormat, null);
		while(it.hasNext()){
			Resource r = it.next().getObject();
			assertTrue(r instanceof Literal);
			assertTrue(expected.remove(((Literal)r).getLexicalForm()));
		}
		assertTrue(expected.isEmpty());
	}

	@Test
	public void textCsvPost() throws Exception {
		log.info("> test CSV");
		String acceptType = "text/turtle";
		ResponseBodyData result = validateAsyncTransformerRequest(BASE_URI, 
				"text/csv;charset=UTF-8", CSV_CONTENT, acceptType);
		Graph graph = parser.parse(result.asInputStream(), acceptType);
		int rowsCount = 0;
		Iterator<Triple> it = graph.filter(null, RDF.type, SINDICE_ROW);
		log.info("Assert CSV to RDF results");
		while(it.hasNext()){
			NonLiteral rowRes = it.next().getSubject();
			assertTrue(rowRes instanceof UriRef);
			log.info(" - asser Row {}", rowRes);
			//TODO: assert row contents
			rowsCount++;
		}
		assertEquals(CSV_LINE_COUNT, rowsCount);
	}

	@Test
	public void testHtmlRdfaPost() throws Exception {
		log.info("> test HTML with RDFa annottions");
		String acceptType = "text/turtle";
		ResponseBodyData result = validateAsyncTransformerRequest(BASE_URI, 
				"text/html;charset=UTF-8", HTML_RDFA_CONTENT, acceptType);
		Graph graph = parser.parse(result.asInputStream(), acceptType);
		assertTrue(graph.size() > 0);
	}
	
	@Test
	public void testHtmlMicroformatPost() throws Exception {
		log.info("> test HTML with Microformat annotations");
		String acceptType = "text/turtle";
		ResponseBodyData result = validateAsyncTransformerRequest(BASE_URI, 
				"text/html;charset=UTF-8", HTML_MICROFORMAT_CONTENT, acceptType);
		Graph graph = parser.parse(result.asInputStream(), acceptType);
		assertTrue(graph.size() > 0);
	}

	@Test
	public void testHtmlMicrodataPost() throws Exception {
		log.info("> test HTML with schema.org Microdata information");
		String acceptType = "text/turtle";
		ResponseBodyData result = validateAsyncTransformerRequest(BASE_URI, 
				"text/html;charset=UTF-8", HTML_MICRODATA_CONTENT, acceptType);
		Graph graph = parser.parse(result.asInputStream(), acceptType);
		assertTrue(graph.size() > 0);
	}

	@Test
	public void testRdfTranscodingPost() throws Exception {
		log.info("> test RDF/XML file");
		String acceptType = "text/turtle";
		ResponseBodyData result = validateAsyncTransformerRequest(BASE_URI, 
				"application/rdf+xml;charset=UTF-8", RDF_XML_CONTENT, acceptType);
		MGraph graph = new SimpleMGraph(parser.parse(result.asInputStream(), acceptType));
		assertTrue("Transformed Graph has " +graph.size() 
				+ "triples while the original one had "+ RDF_XML_CONTENT_GRAPH.size(),
				graph.size() >= RDF_XML_CONTENT_GRAPH.size());
		//validate that all triples of the original graph are still present
		log.info(" - validate if the {}triples of the source graph are present in "
				+ "the transformation results!");
		for(Triple t : RDF_XML_CONTENT_GRAPH){
			assertTrue("missing triple "+t, graph.remove(t));
		}
		//log additional triples for now
		// TODO: not sure if this should be considered as an error or if it is
		//       ok if any23 adds some transformation meta information
		if(!graph.isEmpty()){
			log.info(" - {} additional Triples found in transformed Grpah!");
			for(Triple t : graph){
				log.info("    {}",t);
			}
		}
	}

	
	/**
	 * This uses the {@link #HTML_RDFA_CONTENT} and the {@link #CSV_CONTENT}
	 * but uses <code>application/octet-stream</code> as Content-Type header of
	 * the request. So this validates that Any23 is able to detect the 
	 * content-type of the parsed data.
	 * @throws Exception
	 */
	@Test
	public void testUnknownContentTypePost() throws Exception {
		log.info("> test application/octet-stream requests");
		String contentType = "application/octet-stream";
		String acceptType = "text/turtle";
		ResponseBodyData result;
		Graph graph;
		log.info(" - HTML with RDFa");
		result = validateAsyncTransformerRequest(BASE_URI, 
				contentType, HTML_RDFA_CONTENT, acceptType);
		graph = parser.parse(result.asInputStream(), acceptType);
		assertTrue(graph.size() > 0);
		log.info(" - CSV");
		result = validateAsyncTransformerRequest(BASE_URI, 
				contentType, CSV_CONTENT, acceptType);
		graph = parser.parse(result.asInputStream(), acceptType);
		assertTrue(graph.size() > 0);
	}

	
	/**
	 * Helper method that sends an transformer request to the postURI using the
	 * parsed content-type and content and requesting the parsed accept header.
	 * <p>
	 * This will send the initial transformation request and assert that is was
	 * accepted. After that it will try to obtain the results for a minimum of
	 * 30sec and maximum of 60sec by polling the Job URI every 3sec.<p>
	 * Every response is validated and - if available - the final results are
	 * returned in the form of {@link ResponseBodyData}. <p>
	 * <b>TODO:</b> THis is a generally useful utility and SHOULD BE moved to
	 * some transformer test module
	 * @param postURI the URI to post to
	 * @param contentType the content type
	 * @param content the content
	 * @param acceptType the accept content type
	 * @return the response data
	 * @throws InterruptedException
	 */
	public static ResponseBodyData validateAsyncTransformerRequest(String postURI,
			String contentType, byte[] content, final String acceptType)
			throws InterruptedException {
		//(1) send the transformer request
		Response response = RestAssured.given().header("Accept", acceptType)
				.contentType(contentType).body(content)
				.expect().statusCode(HttpStatus.SC_ACCEPTED)
				.when()
				.post(postURI);
		String location = response.getHeader("location");
		log.info(" - accepted with location: {}", location);
		
		//(2) try for min 30sec max 60sec to retrieve the transformation results
		String locationUri = (postURI.charAt(postURI.length() - 1) == '/' ? 
				postURI.substring(0, postURI.length()-1) : postURI) + location;
		UriRef locationUriRef = new UriRef(locationUri);
		
		long start = System.currentTimeMillis();
		int retry = 0;
		for(; retry < MAX_RETRY && System.currentTimeMillis() - start < MAX_TIMEOUT; retry++){
			response = RestAssured.given().header("Accept", acceptType)
					.when().get(locationUri);
			int status = response.getStatusCode();
			if(status >= 200 && status < 300){
				long duration = System.currentTimeMillis()-start;
				String respContentType = response.getHeader("Content-Type");
				log.debug("response-body: {}", response.getBody().print());
				if(response.getStatusCode() == HttpStatus.SC_ACCEPTED){
					log.info(" ... assert Acceptd (after {}ms)", duration);
					Graph graph = parser.parse(
							response.getBody().asInputStream(), 
							response.getContentType());
					assertEquals(1, graph.size());
					assertTrue(graph.contains(new TripleImpl(
							locationUriRef, TRANSFORMER.status, TRANSFORMER.Processing)));
				} else if(response.getStatusCode() == HttpStatus.SC_OK){
					log.info(" ... assert Results (after {}ms)", duration);
					//assert that the content-type is the accept header
					assertTrue(respContentType != null && respContentType.startsWith(
							acceptType));
					return response.getBody();
				} else {
					fail("Unexpected 2xx status code " + response.getStatusCode()
						+ " for request in "+ locationUri + "!");
				}
			} else {
				if(response.getBody() != null){
					log.error("ResponseBody: \n {}",response.getBody().print());
				}
				fail("Unexpceted Response Code " + response.getStatusLine()
						+ " for Request on "+ locationUri+ "!");
			}
			synchronized (postURI) {
				postURI.wait(RETRY_WAIT);
			}
		}
		//NOTE: uncomment for debugging so that the server is not killed as the
		//      timeout is over ...
//		synchronized (postURI) {
//			postURI.wait();
//		}
		fail("Timeout after " + retry + "/"+MAX_RETRY+" rtries and/or " 
				+ (System.currentTimeMillis()-start)/1000 + "/"+(MAX_TIMEOUT/1000)+"sec");
		return null;
	}

	public static int findFreePort() {
		int port = 0;
		try (ServerSocket server = new ServerSocket(0);) {
			port = server.getLocalPort();
		} catch (Exception e) {
			throw new RuntimeException("unable to find a free port");
		}
		log.info(" - run transformer Tests on port {}",port);
		return port;
	}
	
	public static void main(String[] args) {
		Literal l1 = new PlainLiteralImpl("test", new Language("EN"));
		Literal l2 = new PlainLiteralImpl("test", new Language("en"));
		Assert.assertEquals(l1, l2);
	}
	
}
