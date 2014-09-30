package eu.fusepool.transformer.any23;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.UUID;

import javax.activation.MimeType;

import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import eu.fusepool.p3.transformer.commons.Entity;

/**
 * {@link Entity} that keeps data in an XZ compressed tmp file.
 * @author Rupert Westenthler
 *
 */
public class TmpFileEntity implements Entity, Closeable {
	
	private final Logger log = LoggerFactory.getLogger(TmpFileEntity.class);

	private final File tmpFile;
	private final MimeType type;

	public TmpFileEntity(String requestId, MimeType mime) throws IOException {
		String prefix;
		if(requestId == null || requestId.length() < 3){
			prefix = UUID.randomUUID().toString();
		} else {
			prefix = requestId;
		}
		log.debug(" - prefix: {}",prefix);
		tmpFile = File.createTempFile(prefix, ".entity");
		tmpFile.deleteOnExit();
		log.debug(" - tmpFile: {}",tmpFile);
		type = mime;
	}
	/**
	 * Creates an {@link OutputStream} for the tmp file used by this entity to
	 * cache the data until they are requestsd
	 * @return
	 * @throws IOException
	 */
	public OutputStream getWriter() throws IOException {
		return new XZCompressorOutputStream(new FileOutputStream(tmpFile));
	}
	
	@Override
	public MimeType getType() {
		return type;
	}

	@Override
	public InputStream getData() throws IOException {
		return new XZCompressorInputStream(new FileInputStream(tmpFile));
	}

	@Override
	public URI getContentLocation() {
		return null;
	}

	@Override
	public void writeData(OutputStream out) throws IOException {
		InputStream in = getData();
		try {
			IOUtils.copy(in, out);
		} finally {
			IOUtils.closeQuietly(in);
		}
	}
	/**
	 * Deletes the tmp file
	 */
	@Override
	public void close() throws IOException {
		if(tmpFile.isFile()){
			log.debug(" - clean {}", tmpFile);
			tmpFile.delete();
		}
	}
	@Override
	protected void finalize() throws Throwable {
		close();
		super.finalize();
	}

}
