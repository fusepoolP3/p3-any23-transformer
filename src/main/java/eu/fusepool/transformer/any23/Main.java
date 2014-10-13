package eu.fusepool.transformer.any23;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Properties;

import org.apache.any23.extractor.ExtractionParameters.ValidationMode;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.PosixParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import eu.fusepool.p3.transformer.server.TransformerServer;

/**
 * Main for the Any23 transformer.<p>
 * Uses the {@link TransformerServer} to startup an environment based on an
 * embedded Jetty server.<p>
 * The <code>-h</code> option will print an help screen with the different
 * options.
 * @author westei
 *
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);
    
    private static final Options options;
    private static final int DEFAULT_PORT = 8080;
    
    static {
        options = new Options();
        options.addOption("h", "help", false, "display this help and exit");
        options.addOption("p","port",true, 
            String.format("the port for the Any23 transformer (default: %s)",
                DEFAULT_PORT));
        options.addOption("c", "config",true, "The Any23 configuration file. "
                + "Will be applied on top of the Any23 default configuration");
        options.addOption("m", "mode", true, "The validation mode used by Any23 ("
                + "options: "+ Arrays.toString(ValidationMode.values())+", default:" 
                + Any23Transformer.DEFAULT_VALIDATION_MODE + ")");
        options.addOption("x", "core-pool", true, "The core pool size of the thread "
                + "pool used to transform parsed resources (default: "
                + Any23Transformer.CORE_POOL_SIZE + ")");
        options.addOption("y", "max-pool", true, "The maximum pool size of the thread "
                + "pool used to transform parsed resources (default: "
                + Any23Transformer.MAX_POOL_SIZE + ")");
        options.addOption("z", "keep-alive", true, "The maximum time that excess "
                + "idle threads (default: " + Any23Transformer.KEEP_ALIVE_TIME + ")");

    }

    
    public static void main(String[] args) throws Exception {
        log.info("> Any23 Transforme Server");
        CommandLineParser parser = new PosixParser();
        CommandLine line = parser.parse(options, args);
        args = line.getArgs();
        if(line.hasOption('h') || args.length <= 0){
            printHelp();
            System.exit(0);
        }
        int port = -1;
        if(line.hasOption('p')){
            String portStr = line.getOptionValue('p');
            try {
                port = Integer.parseInt(portStr);
                if(port <= 0){
                    log.error("The parsed Port '{}' MUST BE an positive integer", portStr);
                    System.exit(1);
                }
            } catch (NumberFormatException e) {
                log.error(" parsed Port '{}' is not an integer", portStr);
                System.exit(1);
            }
        } else {
            port = DEFAULT_PORT;
        }
        log.info("    - port: {}",port);
        
        Properties config = null;
        if(line.hasOption('c')){
            File configFile = new File(line.getOptionValue('c'));
            if(configFile.isFile()){
                config = new Properties();
                try {
                    config.load(new FileInputStream(configFile));
                } catch(IOException e) {
                    log.error("Unable to read properties from configuration file " +
                            configFile + "(message: "+e.getMessage()+")!", e);
                    System.exit(1);
                }
            } else {
                log.error("Parsed configuration file '{} (absolute: {}') does not exist!",
                        configFile, configFile.getAbsolutePath());
                System.exit(1);
            }
        }
        log.info("    - config: {}",config == null || config.isEmpty() ? "default" : "");
        if(log.isInfoEnabled() && config != null){
            for(Enumeration<?> keys = config.propertyNames(); keys.hasMoreElements();){
                String key = keys.nextElement().toString();
                log.info("        {}: {}",key,config.getProperty(key));
            }
        }
        
        ValidationMode mode = null;
        if(line.hasOption('m')){
            String modeStr = line.getOptionValue('m');
            try {
                mode = ValidationMode.valueOf(modeStr);
            } catch (IllegalArgumentException e){
                log.error("Parsed validation mode '"+modeStr+"' is not valid (supported: "
                        + Arrays.toString(ValidationMode.values())+")!");
                System.exit(1);
            }
        }
        log.info("    - mode: {}", mode != null ? mode :
            (Any23Transformer.DEFAULT_VALIDATION_MODE + "(default)"));
        
        int corePoolSize = -1;
        if(line.hasOption('x')){
            String value = line.getOptionValue('x');
            try {
                corePoolSize = Integer.parseInt(value);
                if(corePoolSize <= 0){
                    log.error("The parsed core thread pool size '{}' MUST BE an positive integer", value);
                    System.exit(1);
                }
            } catch (NumberFormatException e) {
                log.error(" parsed parsed core thread pool size '{}' is not an integer", value);
                System.exit(1);
            }
        } else {
            corePoolSize = Any23Transformer.CORE_POOL_SIZE;
        }
        
        int maxPoolSize = -1;
        if(line.hasOption('y')){
            String value = line.getOptionValue('y');
            try {
                maxPoolSize = Integer.parseInt(value);
                if(maxPoolSize <= 0){
                    log.error("The parsed max thread pool size '{}' MUST BE an positive integer", value);
                    System.exit(1);
                }
            } catch (NumberFormatException e) {
                log.error(" parsed parsed max thread pool size '{}' is not an integer", value);
                System.exit(1);
            }
        } else {
            maxPoolSize = Any23Transformer.MAX_POOL_SIZE;
        }

        long keepAliveTime = -1;
        if(line.hasOption('z')){
            String value = line.getOptionValue('z');
            try {
                keepAliveTime = Integer.parseInt(value);
                if(keepAliveTime <= 0){
                    log.error("The parsed keep alive time '{}' MUST BE an positive integer", value);
                    System.exit(1);
                }
            } catch (NumberFormatException e) {
                log.error(" parsed parsed keep alive time '{}' is not an integer", value);
                System.exit(1);
            }
        } else {
            keepAliveTime = Any23Transformer.KEEP_ALIVE_TIME;
        }
        log.info("    - thread pool:[core: {}| max: {}| keep: {}sec]", 
                new Object[]{corePoolSize, maxPoolSize, keepAliveTime});
        
        log.info(" ... init Transformer ...");
        Any23Transformer transformer = new Any23Transformer(config, mode);
        transformer.setCorePoolSize(corePoolSize);
        transformer.setMaxPoolSize(maxPoolSize);
        transformer.setKeepAliveTime(keepAliveTime);
        
        log.info(" ... init Server ...");
        TransformerServer server = new TransformerServer(Integer.parseInt(args[0]));
        log.info(" ... start Server ...");
        server.start(transformer);
        log.info(" ... shutdown ...");
        transformer.close();
    }
    
    /**
     * 
     */
    private static void printHelp() {
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp(
            "java -Xmx{size} -jar {jar-name} [options]",
            "Any23 Transformer: \n",
            options,
            "provided by Fusepool P3 and powered by http://any23.apache.org");
    }

}
