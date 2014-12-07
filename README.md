file-metadata-solr-indexer
==========================

A small java app which reads a whole filesystem, writing it to a local db (h2) and extracting its metadata (tika) into a solr-index, finally allowing metadata searching/comparing files using the power of solr.
Features:
- Allows updates to the solr-index if files on the filesystem have changed. This feature has currently trouble on  large filesystems... :(

**enviroment, depencies, installation, interface, usage...**

**Enviroment:**
This app has only been tested on a gentoo linux x64 machine with orcale java jdk 1.8 x64 and solr 4.9.0 running on the same machine through tomcat7. 

**Depencies:** 
At least Java 1.7
and the following libraries (this list may getting shorter due to unneeded libraries):
(from solr 4.9.0): commons-io-2.3.jar, httpclient-4.3.1.jar, httpcore-4.3.jar, httpmime-4.3.1.jar, noggit-0.5.jar, slf4j-api-1.7.6.jar, solr-solrj-4.9.0.jar, wstx-asl-3.2.7.jar, zookeeper-3.4.6.jar
(from h2): h2-1.4.179.jar
(from tika): tika-app-1.5.jar
You have to obtain these jars from external sites (apache tika, apache solr, h2)

**Installation:**
Setting up the app using eclispe:
This is maybe a weird way but the only one I used to setup this app:
- Create a new java-project in eclipse (v.432 in my case)
- Add my three java classes (Fileadder.java, FIMcli.java, TikaMetadata.java) to the default package
- Set JRE System Library to JavaSE 1.7
- Add all the .jar files from above as referenced libraries to this project
- Now, right click on the project inside eclipse and choose "Export" -> Java->Runnable JAR File
- Launch configuration: FIMcli must be used for main, "Extract required libraries into generated jar"

Setting up the enviroment:
This section is not ready yet. Planned is a setup for solr on gentoo linux using tomcat. However, all configuration-files needed to setup the right solr-core for this app are included in the repo. 

**Interface, usage:**
Command-line based. This java app is executed and controlled through parameters from the command-line.
Parameters are:
--path "/var/data/files" 
--solrurl "http://localhost:8080/solr-example/testcore/"
Explanation: 
--path has to contain the source-path from where to read recursivly all the files 
--solrurl has to be an url to a solr-core with a special config (see solr-config in this repo)

example: java -jar fileindexmanager.jar --path "/var/data/Backups" --solrurl "http://localhost:8080/solr-example/testcore/"
