Any23 Transformer
=================

This module provides a Fusepool P3 Transformer implementation for 
[Apache Any23](any23.apache.org)

The Any23 Transformer is an asynchronous transformer implementation.

Building from Source:
---------------------

This module uses Maven as build system. After cloning just call

    mvn clean install

in the root directory


Installation:
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

Usage:
-----

As the Any23 transformer implements the [Fusepool Transfomer API]
(https://github.com/fusepoolP3/overall-architecture/blob/master/transformer-api.md) 
communication is expected as specified by the Fusepool.

The capabilities of a transformer can be requested by a simple GET request at 
the base URI. The following listing shows the response of the Any23 transformer 
running at localhost at port `8087`

    curl http://localhost:8087/

    <http://localhost:8087/>
        <http://vocab.fusepool.info/transformer#supportedInputFormat>
            "text/csv"^^xsd:string , "application/octet-stream"^^xsd:string ,
            "text/turtle"^^xsd:string , "application/rdf+xml"^^xsd:string ,
            "application/xhtml+xml"^^xsd:string ,  "text/html"^^xsd:string ,
            "text/rdf+nt"^^xsd:string , "application/n-quads"^^xsd:string ,
            "application/ld+json"^^xsd:string ;
        <http://vocab.fusepool.info/transformer#supportedOutputFormat>
            "text/turtle"^^xsd:string .

Based on that one now knows that HTML documents are supported. So next we want 
to send an RDFa 1.1 example document and let the transformer extract the 
contained knowledge. For that we need to POST the content with the correct
`Content-Type` header - `text/html;charset=UTF-8` in in this case the 
RDFa is embedded in an HTML document (XHTML would be an other option). 

    curl -v -X "POST" -H "Content-Type: text/html;charset=UTF-8" \
        -H "Content-Location: http://www.example.org/fusepool/rdfa-example.html" 
        -T "test/resources/rdfa11.html" \
        http://localhost:8087/

NOTE that the above request does set the “Content-Location” header. The parsed 
value will be used as base URI for the extracted knowledge. If this parameter 
is not defined the generated job URI will get used as base URI

As Any23 is implemented as an asynchronous transformer what you will receive on 
this request is a `202 Accept` with the location of the transformation job

    < HTTP/1.1 202 Accepted
    < Date: Fri, 07 Nov 2014 09:09:42 GMT
    < Location: /job/1678699a-ed36-4282-aaf8-1823aea19970
    < Transfer-Encoding: chunked
    < Server: Jetty(9.2.z-SNAPSHOT)

No one can send a GET request to the job to retrieve the current status or if 
finished the final result.

    curl http://localhost:8087/job/1678699a-ed36-4282-aaf8-1823aea19970

In this case this will return the extracted Good Relation statements 
serialized as `text/turtle`

