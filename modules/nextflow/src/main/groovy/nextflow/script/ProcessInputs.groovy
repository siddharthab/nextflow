/*
 * Copyright 2013-2023, Seqera Labs
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

package nextflow.script

import groovyx.gpars.dataflow.DataflowReadChannel

/**
 * Models the process inputs.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class ProcessInputs implements List<ProcessInput>, Cloneable {

    @Delegate
    private List<ProcessInput> params = []

    private Map<String,?> vars = [:]

    private Map<String,?> env = [:]

    private List<ProcessFileInput> files = []

    Object stdin

    @Override
    ProcessInputs clone() {
        def result = (ProcessInputs)super.clone()
        result.params = new ArrayList<>(params.size())
        for( ProcessInput param : params ) {
            result.params.add((ProcessInput)param.clone())
        }
        return result
    }

    void addParam(String name) {
        add(new ProcessInput(name))
    }

    void addVariable(String name, Object value) {
        vars.put(name, value)
    }

    void addEnv(String name, Object value) {
        env.put(name, value)
    }

    void addFile(ProcessFileInput file) {
        files.add(file)
    }

    List<String> getNames() {
        return params*.getName()
    }

    List<DataflowReadChannel> getChannels() {
        return params*.getChannel()
    }

    Map<String,?> getVariables() {
        return vars
    }

    Map<String,?> getEnv() {
        return env
    }

    List<ProcessFileInput> getFiles() {
        return files
    }

}