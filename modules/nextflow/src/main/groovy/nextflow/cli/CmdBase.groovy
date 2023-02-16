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

import groovy.transform.CompileStatic
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option

/**
 * Implement command shared methods
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@CompileStatic
@Command(
    headerHeading = '%n',
    abbreviateSynopsis = true,
    descriptionHeading = '%n',
    commandListHeading = '%nCommands:%n',
    requiredOptionMarker = ((char)'*'),
    parameterListHeading = '%nParameters:%n',
    optionListHeading = '%nOptions:%n'
)
class CmdBase implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec

    @Option(names = ['-h','-help'], arity = '0', description = 'Print the command usage', usageHelp = true)
    boolean help

    @Override
    void run() {
        spec.commandLine().usage(System.err)
    }

}
