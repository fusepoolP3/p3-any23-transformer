package eu.fusepool.transformer.any23;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
import org.eclipse.jetty.util.ConcurrentHashSet;
import org.eclipse.jetty.util.log.Log;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import eu.fusepool.p3.transformer.AsyncTransformer;
import eu.fusepool.p3.transformer.HttpRequestEntity;
import eu.fusepool.p3.transformer.AsyncTransformer.CallBackHandler;
import eu.fusepool.p3.transformer.commons.Entity;
import eu.fusepool.p3.transformer.commons.util.WritingEntity;

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
    ExtractionParameters extractionParameters;
    private final Any23 any23;
    
    protected final Set<String> activeRequests = new HashSet<String>();
    protected final ReadWriteLock requestLock = new ReentrantReadWriteLock();

    private CallBackHandler callBackHandler;

    private ExecutorService executor;
    
    int corePoolSize = CORE_POOL_SIZE;
    int maxPoolSize = MAX_POOL_SIZE;
    long keepAliveTime = KEEP_ALIVE_TIME;
    
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
        extractionParameters = new ExtractionParameters(this.config, 
                vm == null ? DEFAULT_VALIDATION_MODE : vm);
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
        //syncronously check the request
        HttpServletRequest req = entity.getRequest();
        MimeType type = entity.getType();
        if(DETECTION_MIME_TYPES.contains(type)){
            //TODO detect the type
        }
        log.info("> schedule transformation of Entity[id: {}|type: {}]", requestId, type);
        DocumentSource source = new TmpFileDocumentSource(requestId, entity.getData(), type);
        
        TransformationJob job = new TransformationJob(requestId, source);
        
        requestLock.writeLock().lock();
        try {
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

        public TransformationJob(String id, DocumentSource source) {
            this.id = id;
            this.source = source;

        }

        @Override
        public void run() {
        	log.info(" - transform Entity [id:{}]",id);
            try {
            	long start = System.currentTimeMillis();
                TmpFileEntity transformed = new TmpFileEntity(id, OUTPUT);
                OutputStream out = transformed.getWriter();
                try {
                    TripleHandler handler = new TurtleWriter(out);
	            	any23.extract(extractionParameters, source, handler, UTF8.name());
	            	handler.close();
                } finally {
	            	IOUtils.closeQuietly(out);
                }
                requestLock.writeLock().lock();
                try {
                    activeRequests.remove(id);
                    getCallBackHandler().responseAvailable(id, transformed);
                	log.info(" - transformed Entity [id:{}] in {}ms",id,
                			System.currentTimeMillis()-start);
                } finally {
                    requestLock.writeLock().unlock();
                }
            } catch (IOException e){
                getCallBackHandler().reportException(id, e);
            } catch (ExtractionException e) {
                getCallBackHandler().reportException(id, e);
            } catch (TripleHandlerException e) {
                getCallBackHandler().reportException(id, e);
			} finally {
            	if(source instanceof Closeable){
            		try {
						((Closeable)source).close();
					} catch (IOException e) { /* ignore */ }
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
