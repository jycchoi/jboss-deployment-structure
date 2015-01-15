/*
 * Copyright [2015] Mario Mohr <mario_mohr@web.de>
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

package com.github.mamohr.gradle.deploymentstructure

import nebula.test.IntegrationSpec
import nebula.test.functional.ExecutionResult
import org.gradle.api.ProjectConfigurationException
import org.gradle.api.tasks.StopExecutionException

import javax.xml.XMLConstants
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory

class JBossDeploymentStructureIntSpec extends IntegrationSpec {
    def 'applying the plugin creates jboss-deployment-structure.xml'() {
        buildFile << '''
            apply plugin: 'ear'
            apply plugin: 'com.github.mamohr.jboss-deployment-structure'

        '''
        when:
        ExecutionResult result = runTasks('ear')

        then:
        result.wasExecuted("createJBossDeploymentStructure")
        fileExists('build/createJBossDeploymentStructure/jboss-deployment-structure.xml')
        fileIsValidForSchema(file('build/createJBossDeploymentStructure/jboss-deployment-structure.xml'))
        result.failure == null
    }

    def 'jbossDeploymentStructure extension configuraion is saved to xml'() {

        buildFile << '''
            apply plugin: 'ear'
            apply plugin: 'com.github.mamohr.jboss-deployment-structure'

            jbossDeploymentStructure {
                dependency 'javax.faces.api:1.2'
                exclude 'javax.faces.api'
                subdeployments {
                    'my-war.war' {
                        dependency 'another.module'
                    }
                }
            }

        '''
        when:
        ExecutionResult result = runTasks('ear')

        then:
        result.wasExecuted("createJBossDeploymentStructure")
        fileIsValidForSchema(file('build/createJBossDeploymentStructure/jboss-deployment-structure.xml'))
        result.failure == null
    }

    def 'withXml can be used to change xml'() {
        buildFile << '''
            apply plugin: 'ear'
            apply plugin: 'com.github.mamohr.jboss-deployment-structure'

            jbossDeploymentStructure {
                dependency 'javax.faces.api:1.2'
                exclude 'javax.faces.api'
                subdeployments {
                    'my-war.war' {
                        dependency 'another.module'
                    }
                }
                withXml { node -> node.children().clear()}
            }

        '''
        when:
        ExecutionResult result = runTasks('ear')

        then:
        result.wasExecuted("createJBossDeploymentStructure")
        fileIsValidForSchema(file('build/createJBossDeploymentStructure/jboss-deployment-structure.xml'))
        XmlParser parser = new XmlParser()
        def node = parser.parse(file('build/createJBossDeploymentStructure/jboss-deployment-structure.xml'))
        node.children().isEmpty()
        result.failure == null
    }

    def 'project deploy dependency creates subdeployment'() {
        helper.addSubproject("module")
        buildFile << '''
            apply plugin: 'ear'
            apply plugin: 'com.github.mamohr.jboss-deployment-structure'

            dependencies {
                deploy project(':module')
            }

            subprojects {
                apply plugin: 'java'
            }

        '''
        when:
        ExecutionResult result = runTasks('ear')

        then:
        result.wasExecuted("createJBossDeploymentStructure")
        fileIsValidForSchema(file('build/createJBossDeploymentStructure/jboss-deployment-structure.xml'))
        XmlParser parser = new XmlParser()
        def node = parser.parse(file('build/createJBossDeploymentStructure/jboss-deployment-structure.xml'))
        ((NodeList) node.get('sub-deployment')).size() == 1
        result.failure == null
    }

    def 'global exclude is propagated to deployment and all subdeployments'() {
        helper.addSubproject('module')
        buildFile << '''
            apply plugin: 'ear'
            apply plugin: 'com.github.mamohr.jboss-deployment-structure'

            dependencies {
                deploy project(':module')
            }

            jbossDeploymentStructure {
                globalExclude 'javax.faces.api:1.2'
            }

            subprojects {
                apply plugin: 'java'
            }

        '''
        when:
        ExecutionResult result = runTasks('ear')

        then:
        result.wasExecuted("createJBossDeploymentStructure")
        fileIsValidForSchema(file('build/createJBossDeploymentStructure/jboss-deployment-structure.xml'))
        XmlParser parser = new XmlParser()
        def node = parser.parse(file('build/createJBossDeploymentStructure/jboss-deployment-structure.xml'))
        node.'sub-deployment'.exclusions.module.@name.get(0) == 'javax.faces.api'
        node.'deployment'.exclusions.module.@name.get(0) == 'javax.faces.api'
        result.failure == null
    }


    def 'Subdeployment in subproject is merged to subdeployment in ear'() {
        String subprojectGradle = '''
        apply plugin: 'java'
        apply plugin: 'com.github.mamohr.jboss-deployment-structure'

        jbossSubdeployment {
            exclude 'not_needed:1.2'
        }
        '''

        helper.addSubproject('module', subprojectGradle)
        buildFile << '''
        apply plugin: 'ear'
            apply plugin: 'com.github.mamohr.jboss-deployment-structure\'

            dependencies {
                deploy project(':module')
            }

            jbossDeploymentStructure {
                subdeployments {
                    'module.jar' {
                        exclude 'second_unneeded_module'
                    }
                }
            }

            subprojects {
                apply plugin: 'java'
            }
        '''
        when:
        runTasks('ear')
        then:
        fileIsValidForSchema(file('build/createJBossDeploymentStructure/jboss-deployment-structure.xml'))
        XmlParser parser = new XmlParser()
        def node = parser.parse(file('build/createJBossDeploymentStructure/jboss-deployment-structure.xml'))
        node.'sub-deployment'.size() == 1
        node.'sub-deployment'.exclusions.module.size() == 2

    }

    def 'applying to project without jar, war or ear tasks throws exception'() {
        buildFile << '''
            apply plugin: 'com.github.mamohr.jboss-deployment-structure'
        '''
        when:
        ExecutionResult result = runTasks(CreateJBossDeploymentStructureTask.TASK_NAME)
        then:
        result.failure != null
    }

    boolean fileIsValidForSchema(File file) {
        def factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)

        def stream = JBossDeploymentStructureIntSpec.getResourceAsStream('/jboss-deployment-structure-1_2.xsd')
        def schema = factory.newSchema(new StreamSource(stream))
        def validator = schema.newValidator()
        validator.validate(new StreamSource(new FileInputStream(file)))
        return true
    }

}