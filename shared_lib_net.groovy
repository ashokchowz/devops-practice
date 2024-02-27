[6:24 pm] Sankuri Ashok Babu
#!/usr/bin/env groovy

/**

* build_net.groovy

*

* Jenkins pipeline for building a .NET project based on MSBuild. This should

* consolidate all the different .NET build templates that already exist.

*/
 
import net.sf.json.JSONObject

//import groovy.json.JsonSlurper

//import groovy.json.JsonSlurperClassic

//import net.sf.json.groovy.JsonSlurper

import com.mitchell.apd.cicd.Constants

import com.mitchell.apd.cicd.MipmStateData

import net.sf.json.JSONArray

import com.mitchell.apd.cicd.BuildHelpers

import groovy.json.JsonOutput
 
 
void call(JSONObject jobConfig, String gitCredentialId) {

    pipeline {

        agent {

            node {

                label "${get('label', jobConfig)}"

                customWorkspace "${get('workspacePath', jobConfig)}"

            }

        }

        options {

            timestamps()
 
            // Having too many builds at once causes issues

            disableConcurrentBuilds()
 
            // Skip default checkout so we can do PR integration

            skipDefaultCheckout("${get.skipDefaultCheckout(jobConfig?.gitRepoURL)}")
 
            // We set a global timeout for .NET builds

            timeout(time: 4, unit: 'HOURS')

        }

         environment{

            TestDLL = "${jobConfig.testConfig.testDlls}"

            Target_Server = "${jobConfig.testConfig.integration.targetServer}"

            MIPM_PACKAGE = "${jobConfig.testConfig.integration.mipmPackage}"

        }

        stages {

            stage('pre-build steps') {

                parallel {

                    stage('get code') {

                        steps {

                            tfGitCheckout("${jobConfig.gitRepoURL}")

                        }

                    }

                    stage('install mipm') {

                        steps {

                            mipm(action: 'install')

                        }

                    }

                }

            }

            stage('call autochain start command') {

                when {

                //    anyOf {

                //        branch 'main'

                //        branch 'release/**'

                //    }

                    environment ignoreCase: true, name: 'PR_BUILD', value: 'false'

                }

                steps {

                    autochain(action: 'start')

                }

            }

            stage('user-defined pre-build scripts') {

                when {

                    expression { return (jobConfig?.preBuildScript != null) }

                }

                steps {

                    log('Running developer defined pre-build scripts. These come from the Seed Job config.')

                    buildScript(jobConfig.preBuildScript, 'powershell')

                }

            }

            stage('restore nuget packages') {

                steps {

                    nuget(action: 'restore', jobConfig: jobConfig, copyContent: true, restoreToPackagesDir: false)

                }

            }

            stage('gather test information') {

                when {

                    expression {

                        return (jobConfig?.testConfig?.unit != null)

                    }

                }

                steps {

                    testProcessor(jobConfig)

                }

            }

            stage('validate npm package-lock.json') {

                when {

                    expression { return false }

                }

                steps {

                    // TODO

                    packageLockValidator()

                }

            }

            stage('set version and tokens in npm package.json') {

                when {

                    expression { return (jobConfig?.artifacts?.npm != null) }

                //    anyOf {

                //        branch 'main'

                //        branch 'release/**'

                //    }

                    environment ignoreCase: true, name: 'PR_BUILD', value: 'false'

                }

                steps {

                    npm(action: 'setVersion', jobConfig: jobConfig)

                    npm(action: 'replaceTokens', jobConfig: jobConfig)

                }

            }

            stage('build, test, sonar') {

                environment {

                    SONAR_SCANNER_OPTS = "-Djavax.net.ssl.trustStore=C:/jenkins/certs/win-cacerts -Djavax.net.ssl.keyStore=C:/jenkins/certs/win-cacerts"

                }

                steps {

                    msbuildLib(jobConfig: jobConfig)

                }

            }

            stage('autochain analysis for msbuild') {

                when {

                    expression { return (jobConfig?.solution != null) }

                //    anyOf {

                //        branch 'main'

                //        branch 'release/**'

                //    }

                    environment ignoreCase: true, name: 'PR_BUILD', value: 'false'

                }

                steps {

                    autochain(action: 'dependencies', targetFile: "${jobConfig.solution}", buildType: 'msbuild', jobConfig: jobConfig)

                }

            }

            stage('user-defined post-build scripts') {

                when {

                    expression { return (jobConfig?.postBuildScript != null) }

                }

                steps {

                    log('Running developer defined post-build scripts. These come from the Seed Job config.')

                    buildScript(jobConfig.postBuildScript, 'powershell')

                }

            }

            stage('uglify js files') {

                when {

                    anyOf {

                         expression { return (jobConfig?.uglify != null) }

                         expression { return (jobConfig?.minJS != null) }

                    }

                }

                steps {

                    uglify(jobConfig.uglify)

                    minjs(jobConfig.minJS)

                }

            }

            stage('nuspec updater') {

                when {

                    expression { return (jobConfig.nuspecUpdater == true) }

                }

                steps {

                    nuspecUpdater(action: 'install', jobConfig: jobConfig)

                }

            }

            stage('copy nuget files') {

                when {

                    expression { return (jobConfig.filesToCopyFromNugetPackages != null) }

                }

                steps {

                    nugetCopyPackageContent(jobConfig)

                }

            }

            stage('remove debug flag from web.config files') {

                steps {

                    updateWebConfig()

                }

            }

            stage('create MiPM configs dynamically') {

                when {

                    expression { return (jobConfig?.generateMipmConfigs) }

                    environment ignoreCase: true, name: 'PR_BUILD', value: 'false'

                }

                steps {

                    createDynamicMipmConfig(jobConfig)

                }

            }

            stage('publish nuget packages') {

                when {

                    expression { return (get.shouldPackageNuget(jobConfig)) }

                //    anyOf {

                //        branch 'main'

                //        branch 'release/**'

                //    }

                    environment ignoreCase: true, name: 'PR_BUILD', value: 'false'

                }

                steps {

                    nuget(action: 'pack', jobConfig: jobConfig, push: true )

                }

            }

            stage('set a newmipm config to target staging repo and publish the integration package') {

                steps {

                    script {

                        // Set the MiPM config to point to the staging repo

                        bat 'newmipm config --write ARTIFACTORY_REPO_PACKAGE_RELEASE=mipm-package-staging'

                        // Publish the integration package 

                        mipm(action: 'package', jobConfig: jobConfig, publish: true, target: 'mipm-package-staging')

                    }

                }

            }

            stage('Install MIPM Package using PowerShell script: Integration Stage') {

        steps {

            withCredentials([[ $class: 'VaultUsernamePasswordCredentialBinding',

                               credentialsId: 'vault-jenkins-slave',

                               usernameVariable: 'USERNAME',

                               passwordVariable: 'PASSWORD']]) {

                script {                           

                    echo "calling integration test method"

                    def integrationTestsPassed = configIntegrationCall(Target_Server)

                    currentBuild.result = integrationTestsPassed ? 'SUCCESS' : 'FAILURE'

                }

            }

        }

    }

//   stage('Record result of Install MIPM Package using PowerShell script: Integration Stage') {

//       steps {

//           script {

//               if (currentBuild.resultIsBetterOrEqualTo('FAILURE')) {

                    // Integration tests failed

//                   error('Integration tests failed. Marking the build as FAILURE.')

//               } else {

                    // Integration tests passed, configure MiPM

//                   sh 'newmipm config --write ARTIFACTORY_REPO_PACKAGE_RELEASE=mipm-package-release'

//               }

//           }

//       }

//   }

 
            stage('create mipm packages') {

                when {

                    expression { return (get.shouldPackageMipm(jobConfig)) }

                //    anyOf {

                //        branch 'main'

                //        branch 'release/**'

                //    }

                    environment ignoreCase: true, name: 'PR_BUILD', value: 'false'

                }

                steps {

                    mipm(action: 'package', jobConfig: jobConfig, publish: true, target: 'target')

                    autochain(action: 'analyzeMipm', jobConfig: jobConfig, scanDirectory: 'target')

                }

            }

	     stage('Deploy to Integration server(s)')

    	    {

                agent

        	{

                    label  'msbuild16&&node14'

                }

            //  when {

            //        expression { return (jobConfig.autoDeployDev == true) }

            //        anyOf {

            //            branch 'main'

            //            branch 'release/**'

            //         }

            //   }

            //    environment {

            //       state = "${jobConfig.stateName}-${jobConfig.stateRelease}.${env.BUILD_NUMBER}"

            //   }

                steps 

		        {

                    withCredentials([[$class: 'VaultUsernamePasswordCredentialBinding', credentialsId: 'vault-corp-svc-cicd-qa-build', passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME']]) 

		            {

                        script

			            {

                            def cdbUrl = "https://cicd-int-estimating-general.aws.int/"

                            def (environmentName, mipmGroup) =  MipmStateData.STATE_ENV_MIPMGROUP_MAP[jobConfig.stateName]

                            def apiUrl = "${cdbUrl}/querycdb?environmentName=${environmentName}&baseenv=DEV"

                          // Execute the HTTP request and capture the response

                            def response = httpRequest(acceptType: 'APPLICATION_JSON', contentType: 'APPLICATION_JSON', httpMode: 'GET', url: apiUrl, 

                                           validResponseCodes: '100:599')

                            // Check if the response is valid

                            if (response.status != 200 || ! response.getContent()) {

                                log.error("CDB api has error response for url :- ${apiUrl} ${response.status} : ${response.getContent()}")

                            }

                           def responseJson = readJson(text: response)

                            def serverName

                            // Check if the response is an array

                            if (responseJson instanceof List) {

                                responseJson.eachWithIndex { server, index ->

                                    serverName = server.serverName

                                println "Server ${index + 1}: ${serverName}"

                                }

                            } else {

                              println "Invalid JSON response: Expected an array."

                            }
 
                            def servers = serverName.split()

                            //Iterate over each server and perform the deployment action

                            servers.each { server ->

                                log.info("Deploying to server: $server" + " with state: ${env.state} and group: $mipmGroup")

                                mipm(action: 'installWinState', remoteServer: server, userName: USERNAME, pwd: PASSWORD, state: "${env.state}", group: mipmGroup)

		 	   }

                        }

                    }

                }

        }

            stage('create npm packages') {

                when {

                    expression { return (jobConfig?.artifacts?.npm != null) }

                //    anyOf {

                //        branch 'main'

                //        branch 'release/**'

                //    }

                    environment ignoreCase: true, name: 'PR_BUILD', value: 'false'

                }

                steps {

                    npm(action: 'publish', jobConfig: jobConfig)

                }

            }

            stage('scan with IQ Server') {

                steps {

                    nexusPolicyEvaluation(

                        advancedProperties: '',

                        failBuildOnNetworkError: false,

                        iqApplication: manualApplication(jobConfig.name),

                        iqStage: 'build',

                        iqScanPatterns: [

                            [scanPattern:  '**/*.csproj'], 

                            [scanPattern:  '**/*.nuspec'],

                            [scanPattern:  '**/packages.config'],

                            [scanPattern:  '**/project.json']

                        ],

                        jobCredentialsId: '')

                }

            }

        }

        post {

            cleanup {

                cleanWs()

            }

            always {

                tfPostPRStatus("${jobConfig.gitRepoURL}", "${env.PR_ID}")

            }

            success {

                autochain(action: 'end', buildStatus: 'SUCCESS')

                automerge("${jobConfig.gitRepoURL}")

                buildDownstream(jobConfig)

                sendEmail('SUCCESS', jobConfig)

            }

            unstable {

                autochain(action: 'end', buildStatus: 'SUCCESS')

                automerge("${jobConfig.gitRepoURL}")

                buildDownstream(jobConfig)

                sendEmail('UNSTABLE', jobConfig)

            }

            failure {

                autochain(action: 'end', buildStatus: 'FAILURE')

                sendEmail('FAILURE', jobConfig)

            }

        }

    }

}
