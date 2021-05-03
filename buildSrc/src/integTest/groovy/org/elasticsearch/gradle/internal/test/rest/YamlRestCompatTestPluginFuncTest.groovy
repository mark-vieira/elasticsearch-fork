/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.gradle.internal.test.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SequenceWriter
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.elasticsearch.gradle.Version
import org.elasticsearch.gradle.fixtures.AbstractRestResourcesFuncTest
import org.elasticsearch.gradle.internal.VersionProperties
import org.gradle.testkit.runner.TaskOutcome

class YamlRestCompatTestPluginFuncTest extends AbstractRestResourcesFuncTest {

    def compatibleVersion = Version.fromString(VersionProperties.getVersions().get("elasticsearch")).getMajor() - 1
    def specIntermediateDir = "restResources/v${compatibleVersion}/yamlSpecs"
    def testIntermediateDir = "restResources/v${compatibleVersion}/yamlTests"
    def transformTask  = ":transformV${compatibleVersion}RestTests"
    def YAML_FACTORY = new YAMLFactory()
    def MAPPER = new ObjectMapper(YAML_FACTORY)
    def READER = MAPPER.readerFor(ObjectNode.class)
    def WRITER = MAPPER.writerFor(ObjectNode.class)

    def "yamlRestCompatTest does nothing when there are no tests"() {
        given:
        internalBuild()
        addSubProject(":distribution:bwc:minor") << """
        configurations { checkout }
        artifacts {
            checkout(new File(projectDir, "checkoutDir"))
        }
        """

        buildFile << """
        plugins {
          id 'elasticsearch.yaml-rest-compat-test'
        }
        """

        when:
        def result = gradleRunner("yamlRestCompatTest").build()

        then:
        result.task(':yamlRestCompatTest').outcome == TaskOutcome.NO_SOURCE
        result.task(':copyRestCompatApiTask').outcome == TaskOutcome.NO_SOURCE
        result.task(':copyRestCompatTestTask').outcome == TaskOutcome.NO_SOURCE
        result.task(transformTask).outcome == TaskOutcome.NO_SOURCE
    }

    def "yamlRestCompatTest executes and copies api and transforms tests from :bwc:minor"() {
        given:
        internalBuild()

        addSubProject(":distribution:bwc:minor") << """
        configurations { checkout }
        artifacts {
            checkout(new File(projectDir, "checkoutDir"))
        }
        """

        buildFile << """
            apply plugin: 'elasticsearch.yaml-rest-compat-test'

            // avoids a dependency problem in this test, the distribution in use here is inconsequential to the test
            import org.elasticsearch.gradle.testclusters.TestDistribution;

            dependencies {
               yamlRestTestImplementation "junit:junit:4.12"
            }

            // can't actually spin up test cluster from this test
           tasks.withType(Test).configureEach{ enabled = false }
        """

        String wrongApi = "wrong_version.json"
        String wrongTest = "wrong_version.yml"
        String additionalTest = "additional_test.yml"
        setupRestResources([wrongApi], [wrongTest]) //setups up resources for current version, which should not be used for this test
        addRestTestsToProject([additionalTest], "yamlRestCompatTest")
        //intentionally adding to yamlRestTest source set since the .classes are copied from there
        file("src/yamlRestTest/java/MockIT.java") << "import org.junit.Test;class MockIT { @Test public void doNothing() { }}"

        String api = "foo.json"
        String test = "10_basic.yml"
        //add the compatible test and api files, these are the prior version's normal yaml rest tests
        file("distribution/bwc/minor/checkoutDir/rest-api-spec/src/main/resources/rest-api-spec/api/" + api) << ""
        file("distribution/bwc/minor/checkoutDir/src/yamlRestTest/resources/rest-api-spec/test/" + test) << ""

        when:
        def result = gradleRunner("yamlRestCompatTest").build()

        then:
        result.task(':yamlRestCompatTest').outcome == TaskOutcome.SKIPPED
        result.task(':copyRestCompatApiTask').outcome == TaskOutcome.SUCCESS
        result.task(':copyRestCompatTestTask').outcome == TaskOutcome.SUCCESS
        result.task(transformTask).outcome == TaskOutcome.SUCCESS

        file("/build/${specIntermediateDir}/rest-api-spec/api/" + api).exists()
        file("/build/${testIntermediateDir}/original/rest-api-spec/test/" + test).exists()
        file("/build/${testIntermediateDir}/transformed/rest-api-spec/test/" + test).exists()
        file("/build/${testIntermediateDir}/original/rest-api-spec/test/" + test).exists()
        file("/build/${testIntermediateDir}/transformed/rest-api-spec/test/" + test).exists()
        file("/build/${testIntermediateDir}/transformed/rest-api-spec/test/" + test).text.contains("headers") //transformation adds this
        file("/build/resources/yamlRestCompatTest/rest-api-spec/test/" + additionalTest).exists()

        //additionalTest is not copied from the prior version, and thus not in the intermediate directory, nor transformed
        file("/build/resources/yamlRestCompatTest/" + testIntermediateDir + "/rest-api-spec/test/" + additionalTest).exists() == false
        file("/build/resources/yamlRestCompatTest/rest-api-spec/test/" + additionalTest).text.contains("headers") == false

        file("/build/classes/java/yamlRestTest/MockIT.class").exists() //The "standard" runner is used to execute the compat test

        file("/build/resources/yamlRestCompatTest/rest-api-spec/api/" + wrongApi).exists() == false
        file("/build/resources/yamlRestCompatTest/" + testIntermediateDir + "/rest-api-spec/test/" + wrongTest).exists() == false
        file("/build/resources/yamlRestCompatTest/rest-api-spec/test/" + wrongTest).exists() == false

        result.task(':copyRestApiSpecsTask').outcome == TaskOutcome.NO_SOURCE
        result.task(':copyYamlTestsTask').outcome == TaskOutcome.NO_SOURCE

        when:
        result = gradleRunner("yamlRestCompatTest").build()

        then:
        result.task(':yamlRestCompatTest').outcome == TaskOutcome.SKIPPED
        result.task(':copyRestCompatApiTask').outcome == TaskOutcome.UP_TO_DATE
        result.task(':copyRestCompatTestTask').outcome == TaskOutcome.UP_TO_DATE
        result.task(transformTask).outcome == TaskOutcome.UP_TO_DATE
    }

    def "yamlRestCompatTest is wired into check and checkRestCompat"() {
        given:

        addSubProject(":distribution:bwc:minor") << """
        configurations { checkout }
        artifacts {
            checkout(new File(projectDir, "checkoutDir"))
        }
        """

        buildFile << """
        plugins {
          id 'elasticsearch.yaml-rest-compat-test'
        }

        """

        when:
        def result = gradleRunner("check").build()

        then:
        result.task(':check').outcome == TaskOutcome.UP_TO_DATE
        result.task(':checkRestCompat').outcome == TaskOutcome.UP_TO_DATE
        result.task(':yamlRestCompatTest').outcome == TaskOutcome.NO_SOURCE
        result.task(':copyRestCompatApiTask').outcome == TaskOutcome.NO_SOURCE
        result.task(':copyRestCompatTestTask').outcome == TaskOutcome.NO_SOURCE
        result.task(transformTask).outcome == TaskOutcome.NO_SOURCE

        when:
        buildFile << """
         ext.bwc_tests_enabled = false
        """
        result = gradleRunner("check").build()

        then:
        result.task(':check').outcome == TaskOutcome.UP_TO_DATE
        result.task(':checkRestCompat').outcome == TaskOutcome.UP_TO_DATE
        result.task(':yamlRestCompatTest').outcome == TaskOutcome.SKIPPED
        result.task(':copyRestCompatApiTask').outcome == TaskOutcome.SKIPPED
        result.task(':copyRestCompatTestTask').outcome == TaskOutcome.SKIPPED
        result.task(transformTask).outcome == TaskOutcome.SKIPPED
    }

    def "transform task executes and works as configured"() {
        given:
        internalBuild()

        addSubProject(":distribution:bwc:minor") << """
        configurations { checkout }
        artifacts {
            checkout(new File(projectDir, "checkoutDir"))
        }
        """

        buildFile << """
            apply plugin: 'elasticsearch.yaml-rest-compat-test'

            // avoids a dependency problem in this test, the distribution in use here is inconsequential to the test
            import org.elasticsearch.gradle.testclusters.TestDistribution;

            dependencies {
               yamlRestTestImplementation "junit:junit:4.12"
            }
            tasks.named("transformV7RestTests").configure({ task ->
              task.replaceValueInMatch("_type", "_doc")
              task.replaceValueInMatch("_source.values", ["z", "x", "y"], "one")
              task.removeMatch("_source.blah")
              task.removeMatch("_source.junk", "two")
              task.addMatch("_source.added", [name: 'jake', likes: 'cheese'], "one")
              task.addWarning("one", "warning1", "warning2")
              task.addWarningRegex("two", "regex warning here .* [a-z]")
              task.addAllowedWarning("added allowed warning")
              task.addAllowedWarningRegex("added allowed warning regex .* [0-9]")
              task.removeWarning("one", "warning to remove")
              task.replaceIsTrue("value_to_replace", "replaced_value")
              task.replaceIsFalse("value_to_replace", "replaced_value")
              task.replaceKeyInMatch("some.key_to_replace", "some.key_that_was_replaced")
              task.replaceKeyInLength("key.in_length_to_replace", "key.in_length_that_was_replaced")
            })
            // can't actually spin up test cluster from this test
           tasks.withType(Test).configureEach{ enabled = false }
        """

        setupRestResources([], [])

        file("distribution/bwc/minor/checkoutDir/src/yamlRestTest/resources/rest-api-spec/test/test.yml" ) << """
        "one":
          - do:
              get:
                index: test
                id: 1
              warnings:
                - "warning to remove"
          - match: { _source.values: ["foo"] }
          - match: { _type: "_foo" }
          - match: { _source.blah: 1234 }
          - match: { _source.junk: true }
          - match: { some.key_to_replace: true }
          - is_true: "value_to_replace"
          - is_false: "value_to_replace"
          - is_true: "value_not_to_replace"
          - is_false: "value_not_to_replace"
          - length: { key.in_length_to_replace: 1 }
        ---
        "two":
          - do:
              get:
                index: test
                id: 1
          - match: { _source.values: ["foo"] }
          - match: { _type: "_foo" }
          - match: { _source.blah: 1234 }
          - match: { _source.junk: true }
          - is_true: "value_to_replace"
          - is_false: "value_to_replace"
          - is_true: "value_not_to_replace"
          - is_false: "value_not_to_replace"

        """.stripIndent()
        when:
        def result = gradleRunner("yamlRestCompatTest").build()

        then:

        result.task(transformTask).outcome == TaskOutcome.SUCCESS


        file("/build/${testIntermediateDir}/transformed/rest-api-spec/test/test.yml" ).exists()
        List<ObjectNode> actual = READER.readValues(file("/build/${testIntermediateDir}/transformed/rest-api-spec/test/test.yml")).readAll()
        List<ObjectNode> expectedAll = READER.readValues(
        """
        ---
        setup:
        - skip:
            features:
            - "headers"
            - "warnings"
            - "warnings_regex"
            - "allowed_warnings"
            - "allowed_warnings_regex"
        ---
        one:
        - do:
            get:
              index: "test"
              id: 1
            warnings:
            - "warning1"
            - "warning2"
            headers:
              Content-Type: "application/vnd.elasticsearch+json;compatible-with=7"
              Accept: "application/vnd.elasticsearch+json;compatible-with=7"
            allowed_warnings:
            - "added allowed warning"
            allowed_warnings_regex:
            - "added allowed warning regex .* [0-9]"
        - match:
            _source.values:
            - "z"
            - "x"
            - "y"
        - match:
            _type: "_doc"
        - match: {}
        - match:
            _source.junk: true
        - match:
            some.key_that_was_replaced : true
        - is_true: "replaced_value"
        - is_false: "replaced_value"
        - is_true: "value_not_to_replace"
        - is_false: "value_not_to_replace"
        - length: { key.in_length_that_was_replaced: 1 }
        - match:
            _source.added:
              name: "jake"
              likes: "cheese"

        ---
        two:
        - do:
            get:
              index: "test"
              id: 1
            headers:
              Content-Type: "application/vnd.elasticsearch+json;compatible-with=7"
              Accept: "application/vnd.elasticsearch+json;compatible-with=7"
            warnings_regex:
            - "regex warning here .* [a-z]"
            allowed_warnings:
            - "added allowed warning"
            allowed_warnings_regex:
            - "added allowed warning regex .* [0-9]"
        - match:
            _source.values:
            - "foo"
        - match:
            _type: "_doc"
        - match: {}
        - match: {}
        - is_true: "replaced_value"
        - is_false: "replaced_value"
        - is_true: "value_not_to_replace"
        - is_false: "value_not_to_replace"
        """.stripIndent()).readAll()

        expectedAll.eachWithIndex{ ObjectNode expected, int i ->
            if(expected != actual.get(i)) {
                println("\nTransformed Test:")
                SequenceWriter sequenceWriter = WRITER.writeValues(System.out)
                for (ObjectNode transformedTest : actual) {
                    sequenceWriter.write(transformedTest)
                }
                sequenceWriter.close()
            }
           assert expected == actual.get(i)
        }

        when:
        result = gradleRunner(transformTask).build()

        then:
        result.task(transformTask).outcome == TaskOutcome.UP_TO_DATE

        when:
        buildFile.write(buildFile.text.replace("blah", "baz"))
        result = gradleRunner(transformTask).build()

        then:
        result.task(transformTask).outcome == TaskOutcome.SUCCESS
    }

}
