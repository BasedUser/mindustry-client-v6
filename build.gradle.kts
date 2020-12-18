buildscript{
    extra{
        fun getArchHash(): String {
            return Properties().with{ p -> p.load(file("gradle.properties").newReader()); return p }["archash"]
        }

        val arcHash = getArcHash()
    }
    
    repositories{
        mavenLocal()
        mavenCentral()
        google()
        maven { url = "https://oss.sonatype.org/content/repositories/snapshots/" }
        jcenter()
        maven{ url = "https://jitpack.io" }
    }

    dependencies{
        classpath("com.mobidevelop.robovm:robovm-gradle-plugin:2.3.11")
        classpath("com.github.anuken:packr:-SNAPSHOT")
        classpath("com.github.Anuken.Arc:packer:$arcHash")
        classpath("com.github.Anuken.Arc:arc-core:$arcHash")
    }
}

allprojects{
    apply plugin: "maven-publish"
    
    version = "release"
    group = "com.github.Anuken"

    extra{
        versionNumber = "6"
        if(!project.hasProperty("clientBuild")) clientBuild = "0"
        if(!project.hasProperty("updateUrl")) updateUrl = "https://api.github.com/repos/blahblahbloopster/mindustry-client-v6/releases/latest"
        if(!project.hasProperty("versionModifier")) versionModifier = "release"
        if(!project.hasProperty("buildversion")) buildversion = "121.0"
        if(!project.hasProperty("versionType")) versionType = "official"
        appName = "Mindustry"
        steamworksVersion = "891ed912791e01fe9ee6237a6497e5212b85c256"
        rhinoVersion = "2617981f706e50b8753155d8e15e326308be3b22"

        loadVersionProps = {
            return Properties().with{p -> p.load(file("../core/assets/version.properties").newReader()); return p }
        }

        debugged = {
            return File(projectDir.parent, "../Mindustry-Debug").exists() && !project.hasProperty("release") && project.hasProperty("args")
        }

        localArc = {
            return !project.hasProperty("release") && File(projectDir.parent, "../Arc").exists()
        }

        arcModule = { String name ->
            if(localArc()){
                return project(":Arc:$name")
            }else{
                //skip to last submodule
                if(name.contains(":")) name = name.split(":").last()
                return "com.github.Anuken.Arc:$name:${getArcHash()}"
            }
        }

        generateDeployName = { String platform ->
            if(platform == "windows"){
                platform += "64"
            }
            platform = platform.capitalize()

            if(platform.endsWith("64") || platform.endsWith("32")){
                platform = "${platform.substring(0, platform.length() - 2)}-${platform.substring(platform.length() - 2)}bit"
            }

            return "[${platform}]${getModifierString()}[${getNeatVersionString()}]${appName}"
        }

        getVersionString = {
            String buildVersion = getBuildVersion()
            return "$versionNumber-$versionModifier-$buildVersion"
        }

        getNeatVersionString = {
            String buildVersion = getBuildVersion()
            return "v$buildVersion"
        }

        getModifierString = {
            if(versionModifier != "release"){
                return "[${versionModifier.toUpperCase()}]"
            }
            return ""
        }

        getBuildVersion = {
            if(!project.hasProperty("buildversion")) return "custom build"
            return project.getProperties()["buildversion"]
        }

        getPackage = {
            return project.extra.mainClassName.substring(0, project.extra.mainClassName.indexOf("desktop") - 1)
        }

        findSdkDir = {
            //null because IntelliJ doesn't get env variables
            def v = System.getenv("ANDROID_HOME")
            if(v != null) return v
            //rootDir is null here, amazing. brilliant.
            def file = File("local.properties")
            if(!file.exists()) file = File("../local.properties")
            def props = Properties().with{p -> p.load(file.newReader()); return p }
            return props.get("sdk.dir")
        }

        generateLocales = {
            def output = "en\n"
            def bundles = File(project(":core").projectDir, "assets/bundles/")
            bundles.listFiles().each{ other ->
                if(other.name == "bundle.properties") return
                output += other.name.substring("bundle".length() + 1, other.name.lastIndexOf(".")) + "\n"
            }
            File(project(":core").projectDir, "assets/locales").text = output
            File(project(":core").projectDir, "assets/basepartnames").text = File(project(":core").projectDir, "assets/baseparts/").list().join("\n")
        }

        writeVersion = {
            def pfile = File(project(":core").projectDir, "assets/version.properties")
            def props = Properties()

            try{
                pfile.createNewFile()
            }catch(Exception ignored){
            }

            if(pfile.exists()){
                props.load(FileInputStream(pfile))

                String buildid = getBuildVersion()
                println("Compiling with build: '$buildid'")

                props["clientBuild"] = clientBuild
                props["updateUrl"] = updateUrl
                props["type"] = versionType
                props["number"] = versionNumber
                props["modifier"] = versionModifier
                props["build"] = buildid

                props.store(pfile.newWriter(), "Autogenerated file. Do not modify.")
            }
        }

        writeProcessors = {
            File(rootDir, "annotations/src/main/resources/META-INF/services/").mkdirs()
            def processorFile = File(rootDir, "annotations/src/main/resources/META-INF/services/javax.annotation.processing.Processor")
            def text = StringBuilder()
            def files = File(rootDir, "annotations/src/main/java")
            files.eachFileRecurse(groovy.io.FileType.FILES){ file ->
                if(file.name.endsWith(".java") && (file.text.contains(" extends BaseProcessor") || (file.text.contains(" extends AbstractProcessor") && !file.text.contains("abstract class")))){
                    text.append(file.path.substring(files.path.length() + 1)).append("\n")
                }
            }

            processorFile.text = text.toString().replace(".java", "").replace("/", ".").replace("\\", ".")
        }

        writePlugins = {
            File(rootDir, "annotations/src/main/resources/META-INF/services/").mkdirs()
            def processorFile = File(rootDir, "annotations/src/main/resources/META-INF/services/com.sun.source.util.Plugin")
            def text = StringBuilder()
            def files = File(rootDir, "annotations/src/main/java")
            files.eachFileRecurse(groovy.io.FileType.FILES){ file ->
                if(file.name.endsWith(".java") && (file.text.contains(" implements Plugin"))){
                    text.append(file.path.substring(files.path.length() + 1)).append("\n")
                }
            }

            processorFile.text = text.toString().replace(".java", "").replace("/", ".").replace("\\", ".")
        }
    }

    repositories{
        mavenLocal()
        mavenCentral()
        maven{ url "https://oss.sonatype.org/content/repositories/snapshots/" }
        maven{ url "https://oss.sonatype.org/content/repositories/releases/" }
        maven{ url "https://jitpack.io" }
        jcenter()
    }

    task clearCache{
        doFirst{
            delete{
                delete "$rootDir/core/assets/cache"
            }
        }
    }

    tasks.withType(JavaCompile){
        targetCompatibility = 8
        sourceCompatibility = 14
        options.encoding = "UTF-8"
        options.compilerArgs += ["-Xlint:deprecation"]
        dependsOn(clearCache)
    }
}

configure(project(":annotations")){
    tasks.withType(JavaCompile){
        targetCompatibility = 8
        sourceCompatibility = 8
    }
}

//compile with java 8 compatibility for everything except the annotation project
configure(subprojects - project(":annotations")){
    tasks.withType(JavaCompile){
        if(JavaVersion.current() != JavaVersion.VERSION_1_8){
            options.compilerArgs.addAll(["--release", "8", "--enable-preview"])
        }

        doFirst{
            options.compilerArgs = options.compilerArgs.findAll{it != "--enable-preview" }
        }
    }

    tasks.withType(Javadoc){
        options{
            addStringOption("Xdoclint:none", "-quiet")
            addBooleanOption("-enable-preview", true)
            addStringOption("-release", "14")
        }
    }
}

project(":desktop"){
    apply plugin: "java"

    compileJava.options.fork = true

    dependencies{
        implementation(project(":core"))
        implementation (arcModule("natives:natives-desktop"))
        implementation (arcModule("natives:natives-freetype-desktop"))
        implementation ("com.github.MinnDevelopment:java-discord-rpc:v2.0.1")

        if(debugged()) implementation (project(":debug"))

        implementation ( "com.github.Anuken:steamworks4j:$steamworksVersion" )

        implementation ( arcModule("backends:backend-sdl") )
    }
}

project(":ios"){
    apply plugin: "java"
    apply plugin: "robovm"

    task incrementConfig{
        def vfile = file("robovm.properties")
        def bversion = getBuildVersion()
        def props = Properties()
        if(vfile.exists()){
            props.load(FileInputStream(vfile))
        }else{
            props["app.id"] = "io.anuke.mindustry"
            props["app.version"] = "6.0"
            props["app.mainclass"] = "mindustry.IOSLauncher"
            props["app.executable"] = "IOSLauncher"
            props["app.name"] = "Mindustry"
        }
        
        props["app.build"] = (!props.containsKey("app.build") ? 40 : props["app.build"].toInteger() + 1) + ""
        if(bversion != "custom build"){
            props["app.version"] = versionNumber + "." + bversion + (bversion.contains(".") ? "" : ".0")
        }
        props.store(vfile.newWriter(), null)
    }

    dependencies{
        implementation ( project(":core") )

        implementation ( arcModule("natives:natives-ios") )
        implementation ( arcModule("natives:natives-freetype-ios") )
        implementation ( arcModule("backends:backend-robovm") )

        compileOnly ( project(":annotations") )
    }
}

project(":core"){
    apply plugin: "java-library"

    compileJava.options.fork = true

    task preGen{
        outputs.upToDateWhen{ false }
        generateLocales()
        writeVersion()
        writeProcessors()
        writePlugins()
    }

    task copyChangelog{
        doLast{
            def props = loadVersionProps()
            def androidVersion = props["androidBuildCode"].toInteger() - 2
            def loglines = file("../changelog").text.split("\n")
            def notice = "[This is a truncated changelog, see Github for full notes]"
            def maxLength = 460

            def androidLogList = [notice] + loglines.findAll{ line -> !line.endsWith("]") || line.endsWith("[Mobile]") || line.endsWith("[Android]")}
            def result = ""
            androidLogList.forEach{line ->
                if(result.length() + line.length() + 1 < maxLength){
                    result += line + "\n"
                }
            }
            def changelogs = file("../fastlane/metadata/android/en-US/changelogs/")
            File(changelogs, androidVersion + ".txt").text = (result)
        }
    }

    dependencies{
        compileJava.dependsOn(preGen)

        api ( "org.lz4:lz4-java:1.4.1" )
        api ( arcModule("arc-core") )
        api ( arcModule("extensions:freetype") )
        api ( arcModule("extensions:g3d") )
        api ( arcModule("extensions:fx") )
        api ( arcModule("extensions:arcnet") )
        api ( "com.github.Anuken:rhino:$rhinoVersion" )
        if(localArc() && debugged()) api arcModule("extensions:recorder")

        compileOnly ( project(":annotations") )
        annotationProcessor ( project(":annotations") )
        annotationProcessor ( "com.github.Anuken:jabel:34e4c172e65b3928cd9eabe1993654ea79c409cd" )

    }
}

project(":server"){
    apply plugin: "java"

    dependencies{
        implementation ( project(":core") )
        implementation ( arcModule("backends:backend-headless") )
    }
}

project(":tests"){
    apply plugin: "java"

    dependencies{
        testImplementation ( project(":core") )
        testImplementation ( "org.junit.jupiter:junit-jupiter-params:5.3.1" )
        testImplementation ( "org.junit.jupiter:junit-jupiter-api:5.3.1" )
        testImplementation ( arcModule("backends:backend-headless") )
        testRuntimeOnly ( "org.junit.jupiter:junit-jupiter-engine:5.3.1" )
    }

    test{
        useJUnitPlatform()
        workingDir = File("../core/assets")
        testLogging {
            exceptionFormat = "full"
            showStandardStreams = true
        }
    }
}

project(":tools"){
    apply plugin: "java"

    dependencies{
        implementation ( project(":core") )

        implementation ( arcModule("natives:natives-desktop") )
        implementation ( arcModule("natives:natives-freetype-desktop") )
        implementation ( arcModule("backends:backend-headless") )
    }
}

project(":annotations"){
    apply plugin: "java-library"

    dependencies{
        implementation ( "com.squareup:javapoet:1.12.1" )
        implementation ( "com.github.Anuken.Arc:arc-core:$arcHash" )
        implementation ( files("${System.getProperty("java.home")}/../lib/tools.jar") )
    }
}

task deployAll{
    task cleanDeployOutput{
        doFirst{
            if(getBuildVersion() == "custom build" || getBuildVersion() == "") throw IllegalArgumentException("----\n\nSET A BUILD NUMBER FIRST!\n\n----")
            if(!project.hasProperty("release")) throw IllegalArgumentException("----\n\nSET THE RELEASE PROJECT PROPERTY FIRST!\n\n----")

            delete{
                delete "deploy/"
            }
        }
    }

    dependsOn ( cleanDeployOutput )
    dependsOn ( "desktop:packrLinux64" )
    dependsOn ( "desktop:packrWindows64" )
    dependsOn ( "desktop:packrWindows32" )
    dependsOn ( "desktop:packrMacOS" )
    dependsOn ( "server:deploy" )
    dependsOn ( "android:deploy" )
}
