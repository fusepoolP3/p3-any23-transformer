package eu.fusepool.transformer.any23;


import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.activation.MimeType;
import javax.activation.MimeTypeParseException;
import javax.servlet.http.HttpServletRequest;

import org.apache.any23.Any23;
import org.apache.any23.configuration.Configuration;
import org.apache.any23.configuration.DefaultConfiguration;
import org.apache.any23.configuration.ModifiableConfiguration;
import org.apache.any23.extractor.ExtractionException;
import org.apache.any23.extractor.ExtractionParameters;
import org.apache.any23.extractor.ExtractionParameters.ValidationMode;
import org.apache.any23.source.DocumentSource;
import org.apache.any23.writer.TripleHandler;
import org.apache.any23.writer.TripleHandlerException;
import org.apache.any23.writer.TurtleWriter;
import org.apache.clerezza.rdf.core.serializedform.SupportedFormat;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import eu.fusepool.p3.transformer.AsyncTransformer;
import eu.fusepool.p3.transformer.HttpRequestEntity;

public class Any23Transformer implements AsyncTransformer, Closeable {

    private final Logger log = LoggerFactory.getLogger(getClass());
    
    public static final long KEEP_ALIVE_TIME = 60L;

    public static final int MAX_POOL_SIZE = 20;

    public static final int CORE_POOL_SIZE = 3;

    /**
     * This transformer uses the {@link ValidationMode#ValidateAndFix} as default
     */
    public static final ValidationMode DEFAULT_VALIDATION_MODE = ValidationMode.ValidateAndFix;

    public static final MimeType BINARY;

    public static final MimeType RDF_XML;
    public static final MimeType TURTLE;
    public static final MimeType N_TRIPLE;
    public static final MimeType N_TRIPLE2;
    public static final MimeType N_QUADS;
    public static final MimeType N3;
    public static final MimeType JSON_LD;
    public static final MimeType CSV;
    public static final MimeType HTML;
    public static final MimeType XHTML;

    public static final MimeType OUTPUT;

    private static final Charset UTF8 = Charset.forName("UTF-8");

    
    static {
        try {
            BINARY = new MimeType("application/octet-stream");
            RDF_XML = new MimeType(SupportedFormat.RDF_XML);
            TURTLE = new MimeType(SupportedFormat.TURTLE);
            N_TRIPLE = new MimeType(SupportedFormat.N_TRIPLE);
            N_TRIPLE2 = new MimeType("application/n-triples");
            N_QUADS = new MimeType("application/n-quads");
            N3 = new MimeType(SupportedFormat.N3);
            JSON_LD = new MimeType("application/ld+json");
            CSV = new MimeType("text/csv");
            HTML = new MimeType("text/html");
            XHTML = new MimeType("application/xhtml+xml");
            
            OUTPUT = new MimeType(SupportedFormat.TURTLE +";charset="+UTF8);
        } catch (MimeTypeParseException e) {
            throw new IllegalStateException(e);
        }
    }
    
    /**
     * 
     */
    private static Set<MimeType> DETECTION_MIME_TYPES = Collections.unmodifiableSet(
            new HashSet<MimeType>(Arrays.asList(BINARY)));
    
    public static final Set<MimeType> INPUT_FORMATS;
    public static final Set<MimeType> OUTPUT_FORMATS;
    
    static {
        Set<MimeType> formats = new HashSet<MimeType>();
        formats.addAll(DETECTION_MIME_TYPES);
        formats.add(HTML);
        formats.add(XHTML);
        formats.add(CSV);
        formats.add(RDF_XML);
        formats.add(TURTLE);
        formats.add(N_TRIPLE);
        formats.add(N_TRIPLE);
        formats.add(N_QUADS);
        formats.add(JSON_LD);
        INPUT_FORMATS = Collections.unmodifiableSet(formats);
        formats = new HashSet<MimeType>();
        formats.add(TURTLE);
        OUTPUT_FORMATS = Collections.unmodifiableSet(formats);
    }
    
    private final Configuration config;
    private final Any23 any23;
    
    protected final Set<String> activeRequests = new HashSet<String>();
    protected final ReadWriteLock requestLock = new ReentrantReadWriteLock();

    private CallBackHandler callBackHandler;

    private ExecutorService executor;
    
    int corePoolSize = CORE_POOL_SIZE;
    int maxPoolSize = MAX_POOL_SIZE;
    long keepAliveTime = KEEP_ALIVE_TIME;

    private final ValidationMode validationMode;
    
    public Any23Transformer() {
        this(null, null);
    }
    
    /**
     * Constructor that allows to customize the Any23 configuration. override/extend parameters of the Any23
     * default configuration
     * @param config the configuration
     */
    public Any23Transformer(Properties config, ValidationMode vm) {
        log.info("> created Any23 transformer ");
        if(config != null && config.size() > 0){
            this.config = DefaultConfiguration.copy();
            for(Enumeration<?> keys = config.propertyNames(); keys.hasMoreElements();){
                String key = keys.nextElement().toString();
                ((ModifiableConfiguration)this.config).setProperty(
                        key, config.getProperty(key));
            }
        } else {
            this.config = DefaultConfiguration.singleton();
        }
        this.validationMode = vm == null ? DEFAULT_VALIDATION_MODE : vm;
        any23 = new Any23(this.config);
    }

    /**
     * Getter for the core thread pool size
     * @return
     */
    public int getCorePoolSize() {
        return corePoolSize;
    }
    /**
     * Setter for the core thread pool size
     * @param corePoolSize
     * @throws IllegalStateException if the transformer was already started
     */
    public void setCorePoolSize(int corePoolSize) {
        if(executor != null){
            throw new IllegalStateException("Transformer already started");
        }
        this.corePoolSize = corePoolSize;
    }
    /**
     * Getter for the maximum thread pool size
     * @return
     */
    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    /**
     * Setter for the maximum thread pool size
     * @param maxPoolSize
     * @throws IllegalStateException if the transformer was already started
     */
    public void setMaxPoolSize(int maxPoolSize) {
        if(executor != null){
            throw new IllegalStateException("Transformer already started");
        }
        this.maxPoolSize = maxPoolSize;
    }
    
    /**
     * Getter for the maximum time that excess idle threads
     * @return
     */
    public long getKeepAliveTime() {
        return keepAliveTime;
    }

    /**
     * Setter for the maximum time that excess idle threads
     * @param maximum time that excess idle threads
     * @throws IllegalStateException if the transformer was already started
     */
    public void setKeepAliveTime(long keepAliveTime) {
        if(executor != null){
            throw new IllegalStateException("Transformer already started");
        }
        this.keepAliveTime = keepAliveTime;
    }

    @Override
    public Set<MimeType> getSupportedInputFormats() {
        return INPUT_FORMATS;
    }

    @Override
    public Set<MimeType> getSupportedOutputFormats() {
        return OUTPUT_FORMATS;
    }

    @Override
    public void activate(CallBackHandler callBackHandler) {
        this.callBackHandler = callBackHandler;
        executor = new ThreadPoolExecutor(corePoolSize, maxPoolSize,
                keepAliveTime, TimeUnit.SECONDS,
                new SynchronousQueue<Runnable>());
    }

    @Override
    public void transform(HttpRequestEntity entity, String requestId)
            throws IOException {
        log.debug("> transform request {}", requestId);
        log.debug(" - mime: {}",entity.getType());
        log.debug(" - contentLoc: {}",entity.getContentLocation());
        //syncronously check the request
        HttpServletRequest req = entity.getRequest();
        
        ExtractionParameters extractionParams = new ExtractionParameters(config, validationMode);
        
        //Create the Document URI form the content location:
        String documentUri;
        URI contentLoc = entity.getContentLocation();
        if(contentLoc != null){
            String contentLocStr = contentLoc.toString();
            if(contentLoc.isAbsolute()){
                documentUri = contentLocStr;
            } else { //relative to the request URI
                StringBuffer uri = req.getRequestURL();
                //check if we need to add a path separator
                if(contentLocStr.charAt(0) != '/' || contentLocStr.charAt(0) != '#' ||
                        uri.charAt(uri.length() - 1) == '/'){
                    uri.append('/');
                }
                uri.append(contentLocStr); //append the relative
                documentUri = uri.toString();
            }
        } else { //no content location fall back to the request ID
            //we need to ensure that there are is a separator between the
            //request URI and the requestID. Also make sure that we do not add
            //two '/'!
            StringBuffer uri = req.getRequestURL();
            boolean slash = uri.charAt(uri.length()-1) == '/';
            if(requestId.charAt(0) == '/' && slash){
                uri.append(requestId.subSequence(1, requestId.length()-1));
            } else {
                if(!slash && requestId.charAt(0) != '#'){
                    uri.append('/');
                }
                uri.append(requestId);
            }
            documentUri = uri.toString();
        }
        log.debug(" - documentUri: {}",documentUri);
        //NOTE: We need to consume the data from the request before we end the
        //      sync. request processing.
        DocumentSource source = new TmpFileDocumentSource(requestId, entity.getData(), 
                entity.getType(), documentUri);
        log.debug(" - documentSource: {}", source);
        //Now create the job for async. processing 
        TransformationJob job = new TransformationJob(requestId, extractionParams, source);
        
        requestLock.writeLock().lock();
        try {
            log.info("> schedule transformation of Entity[id: {} | uri: {} | type: {}]", 
                    new Object[]{requestId, documentUri, entity.getType()});
            executor.submit(job);
            activeRequests.add(requestId);
        } finally {
            requestLock.writeLock().unlock();
        }
    }

    @Override
    public boolean isActive(String requestId) {
        requestLock.readLock().lock();
        try {
            return activeRequests.contains(requestId);
        } finally {
            requestLock.readLock().unlock();
        }
    }

    protected CallBackHandler getCallBackHandler() {
        return callBackHandler;
    }
    
    @Override
    public void close() throws IOException {
        if(executor != null){
            executor.shutdown();
            executor = null;
        }
    }
    
    @Override
    protected void finalize() throws Throwable {
        close();
    }
    
    class TransformationJob implements Runnable{

        private final String id;
        private final DocumentSource source;
        private ExtractionParameters extractionParams;

        public TransformationJob(String id, ExtractionParameters extractionParams,
                DocumentSource source) {
            this.id = id;
            this.extractionParams = extractionParams;
            this.source = source;

        }

        @Override
        public void run() {
            log.info("> Transform Entity [id: {}]",id);
            boolean success = false;
            TmpFileEntity transformed = null;
            Exception ex = null;
            try {
                long start = System.currentTimeMillis();
                transformed = new TmpFileEntity(id, OUTPUT);
                log.debug(" - target: {}",transformed);
                OutputStream out = null;
                TripleHandler handler = null;
                try {
                    out = transformed.getWriter();
                    handler = new TurtleWriter(out);
                    any23.extract(extractionParams, source, handler, UTF8.name());
                    success = true;
                    log.debug(" - transformed in {}ms", System.currentTimeMillis()-start);
                } finally { //close all the streams
                    if(source instanceof Closeable){
                        log.trace(" - close {}",source);
                        IOUtils.closeQuietly((Closeable)source);
                    }
                    if(handler != null){
                        log.trace(" - close {}",handler);
                        handler.close();
                    }
                    log.trace(" - close {}",out);
                    IOUtils.closeQuietly(out);
                }
            } catch (IOException e){
            	log.warn("Unable to transform Entity "+id,e);
            	ex = e;
            } catch (ExtractionException e) {
            	log.warn("Unable to transform Entity "+id,e);
            	ex = e;
            } catch (TripleHandlerException e) {
            	log.warn("Unable to transform Entity "+id,e);
            	ex = e;
            } catch (Exception e){
            	if(ex instanceof InterruptedException){
            		Thread.currentThread().interrupt();  // set interrupt flag
            	} else {
            		ex = new RuntimeException("Error while processing Request "+ id, e);
            	}
            	log.error(" - unable to transform job "+id+" (message: "+ex.getMessage()+")!", ex);
            } finally {
            	
                requestLock.writeLock().lock();
                try {
                    activeRequests.remove(id);
                    if(success) {
                    	getCallBackHandler().responseAvailable(id, transformed);
                    } else {
                    	if(ex == null){ //an Error was thrown
                    		ex = new RuntimeException("Error while processing "+id);
                    	} //else catched Exception
                    	getCallBackHandler().reportException(id, ex);
                    }
                } finally {
                    requestLock.writeLock().unlock();
                    //in any case try to close the source
                }
            }
            
        }
        
        @Override
        public int hashCode() {
            return id.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            TransformationJob other = (TransformationJob) obj;
            if (!getOuterType().equals(other.getOuterType()))
                return false;
            if (!id.equals(other.id))
                return false;
            return true;
        }

        private Any23Transformer getOuterType() {
            return Any23Transformer.this;
        }
    }
    
}
