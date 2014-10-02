package eu.fusepool.transformer.any23;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

import javax.activation.MimeType;

import org.apache.any23.source.DocumentSource;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class TmpFileDocumentSource implements DocumentSource , Closeable {

    private final Logger log = LoggerFactory.getLogger(getClass());
    
    private final String type;
    private final long length;

    private File tmpFile;
    
    final private String docUri;

    public TmpFileDocumentSource(String requestId, InputStream in, MimeType type,
            String docUri) throws IOException {
        assert in != null;
        assert type != null;
        assert requestId != null;
        assert docUri != null;
        String prefix;
        if(requestId == null || requestId.length() < 3){
            prefix = UUID.randomUUID().toString();
        } else {
            prefix = requestId;
        }
        this.docUri = docUri;
        log.debug(" - docUri: {}", docUri);
        this.type = type.toString();
        log.debug(" - type: {}", type);
        tmpFile = File.createTempFile(prefix, ".entity");
        tmpFile.deleteOnExit();
        log.debug(" - tmpFile: {}",tmpFile);
        XZCompressorOutputStream out = new XZCompressorOutputStream(new FileOutputStream(tmpFile));
        try {
            length = IOUtils.copyLarge(in, out);
            log.debug(" - copied {}kBytes from Request Body", Math.round(length/100f)/10);
        } finally {
            out.close();
        }
    }
    
    @Override
    public InputStream openInputStream() throws IOException {
        return new BufferedInputStream(new XZCompressorInputStream(
                new FileInputStream(tmpFile)));
    }

    @Override
    public String getContentType() {
        return type;
    }

    @Override
    public long getContentLength() {
        return length;
    }

    @Override
    public String getDocumentURI() {
        return docUri;
    }

    @Override
    public boolean isLocal() {
        return true;
    }
    
    @Override
    public void close() throws IOException {
        if(tmpFile != null){
            log.debug(" - clean {}", tmpFile);
            tmpFile.delete();
        }
    }
    
    @Override
    protected void finalize() throws Throwable {
        close();
    }
    
    @Override
    public String toString() {
        return new StringBuilder(getClass().getSimpleName()).append("[uri: ")
                .append(docUri).append(" | file: ").append(tmpFile)
                .append(" | length: ").append(length/1000)
                .append("kByte]").toString();
    }
}