/*
 * Copyright 2020-2022, Seqera Labs
 * Copyright 2013-2019, Centre for Genomic Regulation (CRG)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nextflow.cli

import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.regex.Pattern

import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import groovy.transform.Memoized
import groovy.util.logging.Slf4j
import groovyx.gpars.GParsConfig
import nextflow.Const
import nextflow.NF
import nextflow.NextflowMeta
import nextflow.config.ConfigBuilder
import nextflow.config.ConfigMap
import nextflow.exception.AbortOperationException
import nextflow.file.FileHelper
import nextflow.plugin.Plugins
import nextflow.scm.AssetManager
import nextflow.script.ScriptFile
import nextflow.script.ScriptRunner
import nextflow.secret.SecretsLoader
import nextflow.util.CustomPoolFactory
import nextflow.util.Duration
import nextflow.util.HistoryFile
import org.yaml.snakeyaml.Yaml
import picocli.CommandLine.Command
import picocli.CommandLine.ITypeConverter
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParentCommand
/**
 * CLI sub-command RUN
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@CompileStatic
@Command(name = 'run', description = "Execute a pipeline project")
class CmdRun extends CmdBase implements HubOptions {

    static final public Pattern RUN_NAME_PATTERN = Pattern.compile(/^[a-z](?:[a-z\d]|[-_](?=[a-z\d])){0,79}$/, Pattern.CASE_INSENSITIVE)

    static final public List<String> VALID_PARAMS_FILE = ['json', 'yml', 'yaml']

    static final public DSL2 = '2'
    static final public DSL1 = '1'

    static {
        // install the custom pool factory for GPars threads
        GParsConfig.poolFactory = new CustomPoolFactory()
    }

    static class DurationConverter implements ITypeConverter<Long> {
        @Override
        Long convert(String value) {
            if( !value ) throw new IllegalArgumentException()
            if( value.isLong() ) {  return value.toLong() }
            return Duration.of(value).toMillis()
        }
    }

    private Map<String,String> sysEnv = System.getenv()

    @ParentCommand
    protected Launcher launcher

    @Parameters(index = '0', description = 'Project name or repository url')
    String pipeline

    @Parameters(index = '1..*', description = 'Pipeline script args')
    List<String> args

    @Option(names = ['-name'], description = 'Assign a mnemonic name to the a pipeline run')
    String runName

    @Option(names = ['-lib'], description = 'Library extension path')
    String libPath

    @Option(names = ['-cache'], arity = '1', description = 'Enable/disable processes caching')
    Boolean cacheable

    @Option(names = ['-resume'], arity = '0..1', fallbackValue = 'last', description = 'Execute the script using the cached results, useful to continue executions that was stopped by an error')
    String resume

    @Option(names = ['-ps','-pool-size'], description = 'Number of threads in the execution pool', hidden = true)
    Integer poolSize

    @Option(names = ['-pi','-poll-interval'], description = 'Executor poll interval (duration string ending with ms|s|m)', converter = DurationConverter, hidden = true)
    long pollInterval

    @Option(names = ['-qs','-queue-size'], description = 'Max number of processes that can be executed in parallel by each executor')
    Integer queueSize

    @Option(names = ['-test'], arity = '0..1', fallbackValue = '%all', description = 'Test a script function with the name specified')
    String test

    @Option(names = ['-w', '-work-dir'], description = 'Directory where intermediate result files are stored')
    String workDir

    @Option(names = ['-bucket-dir'], description = 'Remote bucket where intermediate result files are stored')
    String bucketDir

    @Option(names = ['-params-file'], description = 'Load script parameters from a JSON/YAML file')
    String paramsFile

    @Option(names = ['-process.'], arity = '0..1', fallbackValue = 'true', description = 'Set process options' )
    Map<String,String> process

    @Option(names = ['-e.','-env.'], description = 'Add the specified variable to execution environment')
    Map<String,String> env

    @Option(names = ['-E'], description = 'Exports all current system environment')
    boolean exportSysEnv

    @Option(names = ['-executor.'], arity = '0..1', fallbackValue = 'true', description = 'Set executor options', hidden = true )
    Map<String,String> executorOptions

    @Option(names = ['-r','-revision'], description = 'Revision of the project to run (either a git branch, tag or commit SHA number)')
    String revision

    @Option(names = ['-latest'], description = 'Pull latest changes before run')
    boolean latest

    @Option(names = ['-stdin'], hidden = true)
    boolean stdin

    @Option(names = ['-ansi'], arity = '0', hidden = true)
    void setAnsi(boolean value) {
        launcher.options.ansiLog = value
    }

    @Option(names = ['-ansi-log'], arity = '0..1', description = 'Enable/disable ANSI console logging')
    void setAnsiLog(boolean value) {
        launcher.options.ansiLog = value
    }

    @Option(names = ['-with-tower'], arity = '0..1', fallbackValue = '-', description = 'Monitor workflow execution with Seqera Tower service')
    String withTower

    @Option(names = ['-with-wave'], arity = '0..1', fallbackValue = '-', hidden = true)
    String withWave

    @Option(names = ['-with-fusion'], arity = '0..1', fallbackValue = '-', hidden = true)
    String withFusion

    @Option(names = ['-with-weblog'], arity = '0..1', fallbackValue = '-', description = 'Send workflow status messages via HTTP to target URL')
    String withWebLog

    @Option(names = ['-with-trace'], arity = '0..1', fallbackValue = '-', description = 'Create processes execution tracing file')
    String withTrace

    @Option(names = ['-with-report'], arity = '0..1', fallbackValue = '-', description = 'Create processes execution html report')
    String withReport

    @Option(names = ['-with-timeline'], arity = '0..1', fallbackValue = '-', description = 'Create processes execution timeline file')
    String withTimeline

    @Option(names = ['-with-charliecloud'], arity = '0..1', fallbackValue = '-', description = 'Enable process execution in a Charliecloud container runtime')
    String withCharliecloud

    @Option(names = ['-with-singularity'], arity = '0..1', fallbackValue = '-', description = 'Enable process execution in a Singularity container')
    String withSingularity

    @Option(names = ['-with-apptainer'], arity = '0..1', fallbackValue = '-', description = 'Enable process execution in a Apptainer container')
    String withApptainer

    @Option(names = ['-with-podman'], arity = '0..1', fallbackValue = '-', description = 'Enable process execution in a Podman container')
    String withPodman

    @Option(names = ['-without-podman'], description = 'Disable process execution in a Podman container')
    boolean withoutPodman

    @Option(names = ['-with-docker'], arity = '0..1', fallbackValue = '-', description = 'Enable process execution in a Docker container')
    String withDocker

    @Option(names = ['-without-docker'], arity = '0', description = 'Disable process execution with Docker')
    boolean withoutDocker

    @Option(names = ['-with-mpi'], hidden = true)
    boolean withMpi

    @Option(names = ['-with-dag'], arity = '0..1', fallbackValue = '-', description = 'Create pipeline DAG file')
    String withDag

    @Option(names = ['-bg'], arity = '0', hidden = true)
    void setBackground(boolean value) {
        launcher.options.background = value
    }

    @Option(names = ['-c','-config'], hidden = true )
    List<String> runConfig

    @Option(names = ['-cluster.'], arity = '0..1', fallbackValue = 'true', description = 'Set cluster options', hidden = true )
    Map<String,String> clusterOptions

    @Option(names = ['-profile'], description = 'Choose a configuration profile')
    String profile

    @Option(names = ['-dump-hashes'], description = 'Dump task hash keys for debugging purpose')
    boolean dumpHashes

    @Option(names = ['-dump-channels'], arity = '0..1', fallbackValue = '*', description = 'Dump channels for debugging purpose')
    String dumpChannels

    @Option(names = ['-N','-with-notification'], arity = '0..1', fallbackValue = '-', description = 'Send a notification email on workflow completion to the specified recipients')
    String withNotification

    @Option(names = ['-with-conda'], arity = '0..1', fallbackValue = '-', description = 'Use the specified Conda environment package or file (must end with .yml|.yaml suffix)')
    String withConda

    @Option(names = ['-without-conda'], description = 'Disable the use of Conda environments')
    Boolean withoutConda

    @Option(names = ['-with-spack'], arity = '0..1', fallbackValue = '-', description = 'Use the specified Spack environment package or file (must end with .yaml suffix)')
    String withSpack

    @Option(names = ['-without-spack'], description = 'Disable the use of Spack environments')
    Boolean withoutSpack

    @Option(names = ['-offline'], description = 'Do not check for remote project updates')
    boolean offline = sysEnv.get('NXF_OFFLINE')=='true'

    @Option(names = ['-entry'], arity = '1', description = 'Entry workflow name to be executed')
    String entryName

    @Option(names = ['-dsl1'], arity = '0..1', description = 'Execute the workflow using DSL1 syntax')
    boolean dsl1

    @Option(names = ['-dsl2'], arity = '0..1', description = 'Execute the workflow using DSL2 syntax')
    boolean dsl2

    @Option(names = ['-main-script'], description = 'The script file to be executed when launching a project directory or repository' )
    String mainScript

    @Option(names = ['-stub-run','-stub'], arity = '0..1', description = 'Execute the workflow replacing process scripts with command stubs')
    boolean stubRun

    @Option(names = ['-preview'], description = "Run the workflow script skipping the execution of all processes")
    boolean preview

    @Option(names = ['-plugins'], description = 'Specify the plugins to be applied for this run e.g. nf-amazon,nf-tower')
    String plugins

    @Option(names = ['-disable-jobs-cancellation'], description = 'Prevent the cancellation of child jobs on execution termination')
    Boolean disableJobsCancellation

    private List<String> pipelineArgs = null

    private Map<String,String> pipelineParams = null

    /**
     * Parse the pipeline args and params from the positional
     * args parsed by picocli. This method assumes that the first
     * positional arg that starts with '--' is the first param,
     * and parses the remaining args as params.
     *
     * NOTE: While the double-dash ('--') notation can be used to
     * distinguish pipeline params from CLI options, it cannot be
     * used to distinguish pipeline params from pipeline args.
     */
    private void parseArgs() {
        // parse pipeline args
        int i = args.findIndexOf { it.startsWith('--') }
        pipelineArgs = args[0..<i]

        // parse pipeline params
        pipelineParams = [:]

        if( i == -1 )
            return

        while( i < args.size() ) {
            String current = args[i++]
            if( !current.startsWith('--') ) {
                throw new IllegalArgumentException("Invalid argument '${current}' -- expected pipeline param or CLI option")
            }

            String key
            String value

            // parse '--param=value'
            if( current.contains('=') ) {
                int split = current.indexOf('=')
                key = current.substring(2, split)
                value = current.substring(split+1)
            }

            // parse '--param value'
            else if( i < args.size() && !args[i].startsWith('--') ) {
                key = current.substring(2)
                value = args[i++]
            }

            // parse '--param1 --param2 ...' as '--param1 true --param2 ...'
            else {
                key = current.substring(2)
                value = 'true'
            }

            pipelineParams.put(key, value)
        }

        log.trace "Parsing pipeline args from CLI: $pipelineArgs"
        log.trace "Parsing pipeline params from CLI: $pipelineParams"
    }

    Boolean getDisableJobsCancellation() {
        return disableJobsCancellation!=null
                ? disableJobsCancellation
                : sysEnv.get('NXF_DISABLE_JOBS_CANCELLATION') as boolean
    }

    List<String> getArgs() {
        if( pipelineArgs == null ) {
            parseArgs()
        }
        return pipelineArgs
    }

    Map<String,String> getParams() {
        if( pipelineParams == null ) {
            parseArgs()
        }
        return pipelineParams
    }

    String getParamsFile() {
        return paramsFile ?: sysEnv.get('NXF_PARAMS_FILE')
    }

    boolean hasParams() {
        return params || getParamsFile()
    }

    @Override
    void run() {
        if( withPodman && withoutPodman )
            throw new AbortOperationException("Command line options `-with-podman` and `-without-podman` cannot be specified at the same time")

        if( withDocker && withoutDocker )
            throw new AbortOperationException("Command line options `-with-docker` and `-without-docker` cannot be specified at the same time")

        if( withConda && withoutConda )
            throw new AbortOperationException("Command line options `-with-conda` and `-without-conda` cannot be specified at the same time")

        if( withSpack && withoutSpack )
            throw new AbortOperationException("Command line options `-with-spack` and `-without-spack` cannot be specified at the same time")

        if( offline && latest )
            throw new AbortOperationException("Command line options `-latest` and `-offline` cannot be specified at the same time")

        if( dsl1 && dsl2 )
            throw new AbortOperationException("Command line options `-dsl1` and `-dsl2` cannot be specified at the same time")

        checkRunName()

        log.info "N E X T F L O W  ~  version ${Const.APP_VER}"
        Plugins.init()

        // -- specify the arguments
        final scriptFile = getScriptFile(pipeline)

        // create the config object
        final builder = new ConfigBuilder()
                .setOptions(launcher.options)
                .setCmdRun(this)
                .setBaseDir(scriptFile.parent)
        final config = builder .build()

        // check DSL syntax in the config
        launchInfo(config, scriptFile)

        // check if NXF_ variables are set in nextflow.config
        checkConfigEnv(config)

        // -- load plugins
        final cfg = plugins ? [plugins: plugins.tokenize(',')] : config
        Plugins.load(cfg)

        // -- load secret provider
        if( SecretsLoader.isEnabled() ) {
            final provider = SecretsLoader.instance.load()
            config.withSecretProvider(provider)
        }

        // -- create a new runner instance
        final runner = new ScriptRunner(config)
        runner.setScript(scriptFile)
        runner.setPreview(this.preview)
        runner.session.profile = profile
        runner.session.commandLine = launcher.cliString
        runner.session.ansiLog = launcher.options.ansiLog
        runner.session.disableJobsCancellation = getDisableJobsCancellation()

        final isTowerEnabled = config.navigate('tower.enabled') as Boolean
        if( isTowerEnabled || log.isTraceEnabled() )
            runner.session.resolvedConfig = ConfigBuilder.resolveConfig(scriptFile.parent, this)
        // note config files are collected during the build process
        // this line should be after `ConfigBuilder#build`
        runner.session.configFiles = builder.parsedConfigFiles
        // set the commit id (if any)
        runner.session.commitId = scriptFile.commitId
        if( this.test ) {
            runner.test(this.test, args)
            return
        }

        def info = CmdInfo.status( log.isTraceEnabled() )
        log.debug( '\n'+info )

        // -- add this run to the local history
        runner.verifyAndTrackHistory(launcher.cliString, runName)

        // -- run it!
        runner.execute(args, this.entryName)
    }

    protected checkConfigEnv(ConfigMap config) {
        // Warn about setting NXF_ environment variables within env config scope
        final env = config.env as Map<String, String>
        for( String name : env.keySet() ) {
            if( name.startsWith('NXF_') && name!='NXF_DEBUG' ) {
                final msg = "Nextflow variables must be defined in the launching environment - The following variable set in the config file is going to be ignored: '$name'"
                log.warn(msg)
            }
        }
    }

    protected void launchInfo(ConfigMap config, ScriptFile scriptFile) {
        // -- determine strict mode
        final defStrict = sysEnv.get('NXF_ENABLE_STRICT') ?: false
        final strictMode = config.navigate('nextflow.enable.strict', defStrict)
        if( strictMode ) {
            log.debug "Enabling nextflow strict mode"
            NextflowMeta.instance.strictMode(true)
        }
        // -- determine dsl mode
        final dsl = detectDslMode(config, scriptFile.main.text, sysEnv)
        NextflowMeta.instance.enableDsl(dsl)
        // -- show launch info
        final ver = NF.dsl2 ? DSL2 : DSL1
        final repo = scriptFile.repository ?: scriptFile.source
        final head = preview ? "* PREVIEW * $scriptFile.repository" : "Launching `$repo`"
        if( scriptFile.repository )
            log.info "${head} [$runName] DSL${ver} - revision: ${scriptFile.revisionInfo}"
        else
            log.info "${head} [$runName] DSL${ver} - revision: ${scriptFile.getScriptId()?.substring(0,10)}"
    }

    static String detectDslMode(ConfigMap config, String scriptText, Map sysEnv) {
        // -- try determine DSL version from config file

        final dsl = config.navigate('nextflow.enable.dsl') as String

        // -- script can still override the DSL version
        final scriptDsl = NextflowMeta.checkDslMode(scriptText)
        if( scriptDsl ) {
            log.debug("Applied DSL=$scriptDsl from script declararion")
            return scriptDsl
        }
        else if( dsl ) {
            log.debug("Applied DSL=$dsl from config declaration")
            return dsl
        }
        // -- if still unknown try probing for DSL1
        if( NextflowMeta.probeDsl1(scriptText) ) {
            log.debug "Applied DSL=1 by probing script field"
            return DSL1
        }

        final envDsl = sysEnv.get('NXF_DEFAULT_DSL')
        if( envDsl ) {
            log.debug "Applied DSL=$envDsl from NXF_DEFAULT_DSL variable"
            return envDsl
        }
        else {
            log.debug "Applied DSL=2 by global default"
            return DSL2
        }
    }

    protected void checkRunName() {
        if( runName == 'last' )
            throw new AbortOperationException("Not a valid run name: `last`")
        if( runName && !matchRunName(runName) )
            throw new AbortOperationException("Not a valid run name: `$runName` -- It must match the pattern $RUN_NAME_PATTERN")

        if( !runName ) {
            if( HistoryFile.disabled() )
                throw new AbortOperationException("Missing workflow run name")
            // -- make sure the generated name does not exist already
            runName = HistoryFile.DEFAULT.generateNextName()
        }

        else if( !HistoryFile.disabled() && HistoryFile.DEFAULT.checkExistsByName(runName) )
            throw new AbortOperationException("Run name `$runName` has been already used -- Specify a different one")
    }

    static protected boolean matchRunName(String name) {
        RUN_NAME_PATTERN.matcher(name).matches()
    }

    protected ScriptFile getScriptFile(String pipelineName) {
        try {
            getScriptFile0(pipelineName)
        }
        catch (IllegalArgumentException | AbortOperationException e) {
            if( e.message.startsWith("Not a valid project name:") && !guessIsRepo(pipelineName)) {
                throw new AbortOperationException("Cannot find script file: $pipelineName")
            }
            else
                throw e
        }
    }

    static protected boolean guessIsRepo(String name) {
        if( FileHelper.getUrlProtocol(name) != null )
            return true
        if( name.startsWith('/') )
            return false
        if( name.startsWith('./') || name.startsWith('../') )
            return false
        if( name.endsWith('.nf') )
            return false
        if( name.count('/') != 1 )
            return false
        return true
    }

    protected ScriptFile getScriptFile0(String pipelineName) {
        assert pipelineName

        /*
         * read from the stdin
         */
        if( pipelineName == '-' ) {
            def file = tryReadFromStdin()
            if( !file )
                throw new AbortOperationException("Cannot access `stdin` stream")

            if( revision )
                throw new AbortOperationException("Revision option cannot be used when running a script from stdin")

            return new ScriptFile(file)
        }

        /*
         * look for a file with the specified pipeline name
         */
        def script = new File(pipelineName)
        if( script.isDirectory()  ) {
            script = mainScript ? new File(mainScript) : new AssetManager().setLocalPath(script).getMainScriptFile()
        }

        if( script.exists() ) {
            if( revision )
                throw new AbortOperationException("Revision option cannot be used when running a local script")
            return new ScriptFile(script)
        }

        /*
         * try to look for a pipeline in the repository
         */
        def manager = new AssetManager(pipelineName, this)
        def repo = manager.getProject()

        boolean checkForUpdate = true
        if( !manager.isRunnable() || latest ) {
            if( offline )
                throw new AbortOperationException("Unknown project `$repo` -- NOTE: automatic download from remote repositories is disabled")
            log.info "Pulling $repo ..."
            def result = manager.download(revision)
            if( result )
                log.info " $result"
            checkForUpdate = false
        }
        // checkout requested revision
        try {
            manager.checkout(revision)
            manager.updateModules()
            final scriptFile = manager.getScriptFile(mainScript)
            if( checkForUpdate && !offline )
                manager.checkRemoteStatus(scriptFile.revisionInfo)
            // return the script file
            return scriptFile
        }
        catch( AbortOperationException e ) {
            throw e
        }
        catch( Exception e ) {
            throw new AbortOperationException("Unknown error accessing project `$repo` -- Repository may be corrupted: ${manager.localPath}", e)
        }

    }

    static protected File tryReadFromStdin() {
        if( !System.in.available() )
            return null

        getScriptFromStream(System.in)
    }

    static protected File getScriptFromStream( InputStream input, String name = 'nextflow' ) {
        input != null
        File result = File.createTempFile(name, null)
        result.deleteOnExit()
        input.withReader { Reader reader -> result << reader }
        return result
    }

    @Memoized  // <-- avoid parse multiple times the same file and params
    Map parsedParams(Map configVars) {

        final result = [:]
        final file = getParamsFile()
        if( file ) {
            def path = validateParamsFile(file)
            def type = path.extension.toLowerCase() ?: null
            if( type == 'json' )
                readJsonFile(path, configVars, result)
            else if( type == 'yml' || type == 'yaml' )
                readYamlFile(path, configVars, result)
        }

        // set the CLI params
        if( !params )
            return result

        for( Map.Entry<String,String> entry : params ) {
            addParam( result, entry.key, entry.value )
        }
        return result
    }


    static final private Pattern DOT_ESCAPED = ~/\\\./
    static final private Pattern DOT_NOT_ESCAPED = ~/(?<!\\)\./

    static protected void addParam(Map params, String key, String value, List path=[], String fullKey=null) {
        if( !fullKey )
            fullKey = key
        final m = DOT_NOT_ESCAPED.matcher(key)
        if( m.find() ) {
            final p = m.start()
            final root = key.substring(0, p)
            if( !root ) throw new AbortOperationException("Invalid parameter name: $fullKey")
            path.add(root)
            def nested = params.get(root)
            if( nested == null ) {
                nested = new LinkedHashMap<>()
                params.put(root, nested)
            }
            else if( nested !instanceof Map ) {
                log.warn "Command line parameter --${path.join('.')} is overwritten by --${fullKey}"
                nested = new LinkedHashMap<>()
                params.put(root, nested)
            }
            addParam((Map)nested, key.substring(p+1), value, path, fullKey)
        }
        else {
            params.put(key.replaceAll(DOT_ESCAPED,'.'), parseParamValue(value))
        }
    }


    static protected parseParamValue(String str ) {

        if ( str == null ) return null

        if ( str.toLowerCase() == 'true') return Boolean.TRUE
        if ( str.toLowerCase() == 'false' ) return Boolean.FALSE

        if ( str==~/\d+(\.\d+)?/ && str.isInteger() ) return str.toInteger()
        if ( str==~/\d+(\.\d+)?/ && str.isLong() ) return str.toLong()
        if ( str==~/\d+(\.\d+)?/ && str.isDouble() ) return str.toDouble()

        return str
    }

    private Path validateParamsFile(String file) {

        def result = FileHelper.asPath(file)
        def ext = result.getExtension()
        if( !VALID_PARAMS_FILE.contains(ext) )
            throw new AbortOperationException("Not a valid params file extension: $file -- It must be one of the following: ${VALID_PARAMS_FILE.join(',')}")

        return result
    }

    static private Pattern PARAMS_VAR = ~/(?m)\$\{(\p{javaJavaIdentifierStart}\p{javaJavaIdentifierPart}*)}/

    protected String replaceVars0(String content, Map binding) {
        content.replaceAll(PARAMS_VAR) { List<String> matcher ->
            // - the regex matcher is represented as list
            // - the first element is the matching string ie. `${something}`
            // - the second element is the group content ie. `something`
            // - make sure the regex contains at least a group otherwise the closure
            // parameter is a string instead of a list of the call fail
            final placeholder = matcher.get(0)
            final key = matcher.get(1)

            if( !binding.containsKey(key) ) {
                final msg = "Missing params file variable: $placeholder"
                if(NF.strictMode)
                    throw new AbortOperationException(msg)
                log.warn msg
                return placeholder
            }

            return binding.get(key)
        }
    }

    private void readJsonFile(Path file, Map configVars, Map result) {
        try {
            def text = configVars ? replaceVars0(file.text, configVars) : file.text
            def json = (Map)new JsonSlurper().parseText(text)
            result.putAll(json)
        }
        catch (NoSuchFileException | FileNotFoundException e) {
            throw new AbortOperationException("Specified params file does not exists: ${file.toUriString()}")
        }
        catch( Exception e ) {
            throw new AbortOperationException("Cannot parse params file: ${file.toUriString()} - Cause: ${e.message}", e)
        }
    }

    private void readYamlFile(Path file, Map configVars, Map result) {
        try {
            def text = configVars ? replaceVars0(file.text, configVars) : file.text
            def yaml = (Map)new Yaml().load(text)
            result.putAll(yaml)
        }
        catch (NoSuchFileException | FileNotFoundException e) {
            throw new AbortOperationException("Specified params file does not exists: ${file.toUriString()}")
        }
        catch( Exception e ) {
            throw new AbortOperationException("Cannot parse params file: ${file.toUriString()}", e)
        }
    }

}
