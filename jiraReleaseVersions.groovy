/*************************************************************************************
# This script creates a new version in the appropriate Jira projects
# 
# Getting started
# ---------------
#
# ### Prerequisites
#
# - This script requires Groovy 3 to run
#
# ### Running
#
# groovy jiraVersions.groovy
#
# Example
#
# groovy jiraVersions.groovy
#
#
# @author: david.silva@storicard.com
*************************************************************************************/

@Grab(group='org.codehaus.groovy.modules.http-builder', module='http-builder', version='0.7.1' )
import groovyx.net.http.*

//define empty class with old name to prevent failure
this.getClass().getClassLoader().getParent().parseClass '''
  package groovy.util.slurpersupport
  class GPathResult{}
'''  

versionsSet = new HashSet()
cleanupBranchesAndPRs = [].withDefault{ [:] }
versionsMapByProject = [:]

dateLimit = new Date()
pattern = "yyyy-MM-dd"
VERSION_NAME_PREFIX = "App v"
numberOfVersionsToCreate = 5
LOWEST_ALLOWED_VERSION = 120
println("For this, you need your userid + api_token in base64")
println("Reach out to David Silva if you need assistance.")
def usrAccessKey = System.console().readLine "Access Key:"
API_TOKEN = "Basic ${usrAccessKey}"

def getProjectsList() {
    API_URL="https://powerupai.atlassian.net/rest/api/3/project/search?maxResults=50"
    def projectsSet = new HashSet()
    println("Getting project list: ${API_URL}")
    while ( API_URL != null && API_URL != '' )
    {
        println("API_URL=${API_URL}")
        // println(API_URL)
        API_PARAMS=[Authorization: "${API_TOKEN}", Accept: 'application/json']
        def JiraResponse = new RESTClient("${API_URL}")
        JiraResponse.setHeaders(API_PARAMS)
        def resp = JiraResponse.get(contentType: "application/json")
        def JiraResponseData = resp.getData()
        API_URL = JiraResponseData.nextPage
        // println("JiraResponseData=${JiraResponseData}")
        // println ""
        // println ""
        // println ""
        // println("JiraResponseData.nextPage=${JiraResponseData.nextPage}")
        // println("JiraResponseData.isLast=${JiraResponseData.isLast}")


        JiraResponseData.values.each { project ->
            projectsSet.add("${project.id}_${project.key}")
            // println("project.key ${project.key} - ${project.id}")
        }
    }
    return projectsSet
}

def getProjectsVersions(String projectKey) {
    API_URL="https://powerupai.atlassian.net/rest/api/3/project/${projectKey.split("_")[0]}/versions"
    def projectsSet = new HashSet()
    def versionIdsSet = new HashSet()
    println("Getting project's ${projectKey} version list: ${API_URL}")
    API_PARAMS=[Authorization: "${API_TOKEN}", Accept: 'application/json']
    def JiraResponse = new RESTClient("${API_URL}")
    JiraResponse.setHeaders(API_PARAMS)
    def resp = JiraResponse.get(contentType: "application/json")
    def JiraResponseData = resp.getData()
    JiraResponseData.each { projectVersion ->
    // println("projectKey ${projectKey} - projectVersion ${projectVersion}")
    if (projectVersion != null && projectVersion.archived != true && projectVersion.name != null ){
        defVer = "1.0.0"
        println("projectVersion.name ${projectVersion.name} - ${projectVersion.name == defVer}")
        if(projectVersion.name == defVer){
                    projectsSet.add(defVer)
                    versionIdsSet.add(projectVersion.id)
        } 
        verName = projectVersion.name.replaceAll("[^\\d.]", "")
            try {
                currVer = Float.parseFloat(verName)
                // println("projectKey ${projectKey} - currVer ${currVer} - LOWEST_ALLOWED_VERSION ${LOWEST_ALLOWED_VERSION} - currVer < LOWEST_ALLOWED_VERSION ${currVer < LOWEST_ALLOWED_VERSION}")
                if( currVer < LOWEST_ALLOWED_VERSION){
                    projectsSet.add(currVer)
                    versionIdsSet.add(projectVersion.id)
                }
            } catch(Exception e) {
                println("Error!! - ${e.message}")
            }
        }
    }
    println("Versions for ${projectKey} project - ${projectsSet}")
    return versionIdsSet.size() > 0 ? versionIdsSet : null
}

def prepareNewVersions(String projectKey, int currVersion) {
    def projectVersionsSet = new HashSet()
    projectVersionsMap = [].withDefault{ [:] }
    def minVersion = versionsSet.min()
    def maxVersion = minVersion + numberOfVersionsToCreate

    if(currVersion < maxVersion){
        for( currVersion; currVersion < maxVersion; ) {
            ++currVersion
            projectVersionsSet.add("${VERSION_NAME_PREFIX}${currVersion}")
        }
    }
    println("Preparing project's ${projectKey} new versions: ${projectVersionsSet}")
    return projectVersionsSet
}

def releaseVersionsInJira(String projectKey, String versionId) {
    API_URL="https://powerupai.atlassian.net/rest/api/3/version/${versionId}"
    def projectsSet = new HashSet()
    println("Releasing version ${versionId} in project ${projectKey}")
    API_PARAMS=[Authorization: "${API_TOKEN}", Accept: 'application/json']
    def JiraResponse = new RESTClient("${API_URL}")
    JiraResponse.setHeaders(API_PARAMS)
    def requestBody = "{\"id\": \"${versionId}\", \"released\": true, \"archived\": true}"

    println("API_URL=${API_URL}")
    println("API_PARAMS=${API_PARAMS}")
    println("requestBody=${requestBody}")
    try {
        def resp = JiraResponse.put("contentType": "application/json", "body": "${requestBody}")
        def JiraResponseData = resp.getData()
        println("JiraResponseData=${JiraResponseData}")
        if (resp.status == 201) {
            println("Version ${versionId} released on ${projectKey} SUCCESSFULLY")
        } else {
            println("ERROR when releasing version ${versionId} on ${projectKey}. Error code: ${resp.status}")
            println("${JiraResponseData}")
        }
    } catch (Exception e) {
        println("ERROR when releasing version ${versionId} on ${projectKey}. Exception: ${e.getMessage()}")
    }

}

// get list of Jira Projects & their versions
getProjectsList().each { projectKey ->
    projectVersionsToArchive = getProjectsVersions(projectKey)
    if(projectVersionsToArchive != null){
        // println("projectVersionsToArchive ${projectKey} - ${projectVersionsToArchive}")
        versionsMapByProject[projectKey] = projectVersionsToArchive
        // versionsMapByProject[projectKey].addAll(projectVersionsToArchive)
        // versionsSet.addAll(projectVersionsToArchive)
    }
} 
// prepare the new Jira version names
versionsMapByProject.each {projectKey, versionSet ->
    // versionsMapByProject[projectKey] = prepareNewVersions(projectKey, versionSet)
    println("Loop exterior ${projectKey} - ${versionSet}")
    versionSet.each { projectVersionToArchive ->
        println("${projectKey} - ${projectVersionToArchive}")
        releaseVersionsInJira(projectKey, projectVersionToArchive)

    }
    // Integer.parseInt("intentional fail")
}
