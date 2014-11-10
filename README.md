Any23 Transformer
=================

This module provides a Fusepool P3 Transformer implementation for 
[Apache Any23](http://any23.apache.org)

The Any23 Transformer is an asynchronous transformer implementation.

Building from Source:
---------------------

This module uses Maven as build system. After cloning just call

    mvn clean install

in the root directory


Usage:
-----

This module builds a runnable jar. After the build succeeds you can find the
runable jar in the ´/target´ folder.

Calling

    java -Xmx1g -jar any23-transformer-{version}.jar

will run the Any23 Transformer on port `8080` with the default Any23 configuration
and a thread pool of min `3` and max `20` threads.

The command line tool provides the following configuration parameters:

    java -Xmx{size} -jar {jar-name} [options]
    Any23 Transformer:
    
     -c,--config <arg>       The Any23 configuration file. Will be applied on
                             top of the Any23 default configuration
     -h,--help               display this help and exit
     -m,--mode <arg>         The validation mode used by Any23 (options:
                             [None, Validate, ValidateAndFix], default:ValidateAndFix)
     -p,--port <arg>         the port for the Any23 transformer (default: 8080)
     -x,--core-pool <arg>    The core pool size of the thread pool used to
                             transform parsed resources (default: 3)
     -y,--max-pool <arg>     The maximum pool size of the thread pool used to
                             transform parsed resources (default: 20)
     -z,--keep-alive <arg>   The maximum time that excess idle threads (default: 60)


