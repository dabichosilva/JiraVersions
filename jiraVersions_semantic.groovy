/*************************************************************************************
# This script creates a new version in the appropriate Jira projects
# 
# Using examples from:
# - https://gist.github.com/michaellihs/a6621376393821d6d206ccfc8dbf86ec 
# - https://stackoverflow.com/questions/59936014/sort-list-of-version-numbers-groovy
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

enum PatchLevel {
    MAJOR, MINOR, PATCH
}

class SemVer implements Serializable {

    private int major, minor, patch

    SemVer(String version) {
        def versionParts = version.tokenize('.')
        println versionParts
        if (versionParts.size() != 3) {
            throw new IllegalArgumentException("Wrong version format - expected MAJOR.MINOR.PATCH - got ${version}")
        }
        this.major = versionParts[0].toInteger()
        this.minor = versionParts[1].toInteger()
        this.patch = versionParts[2].toInteger()
    }

    SemVer(int major, int minor, int patch) {
        this.major = major
        this.minor = minor
        this.patch = patch
    }

    SemVer bump(PatchLevel patchLevel) {
        switch (patchLevel) {
            case PatchLevel.MAJOR:
                return new SemVer(major + 1, 0, 0)
                break
            case PatchLevel.MINOR:
                return new SemVer(major, minor + 1, 0)
                break
            case PatchLevel.PATCH:
                return new SemVer(major, minor, patch + 1)
                break
        }
        return new SemVer()
    }

    String toString() {
        return "${major}.${minor}.${patch}"
    }

}



versionsSet = new HashSet()
cleanupBranchesAndPRs = [].withDefault{ [:] }
versionsMapByProject = [:]

dateLimit = new Date()
pattern = "yyyy-MM-dd"
VERSION_NAME_PREFIX = "App v"
DEFAULT_VERSION = "1.0.0"
VERSION_MAJOR = 1
VERSION_MINOR = 0
VERSION_PATCH = 0
VERSION_INCREASE_TYPE = PatchLevel.PATCH // major, minor, patch
VERSION_LATEST = -1
VERSION_OLDEST = 0
numberOfVersionsToCreate = 5
println("Please paste your Jira access key")
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
    println("Getting project's ${projectKey} version list: ${API_URL}")
    API_PARAMS=[Authorization: "${API_TOKEN}", Accept: 'application/json']
    def JiraResponse = new RESTClient("${API_URL}")
    JiraResponse.setHeaders(API_PARAMS)
    def resp = JiraResponse.get(contentType: "application/json")
    def JiraResponseData = resp.getData()
    JiraResponseData.each { projectVersion ->
    if (projectVersion != null && projectVersion.archived != true && projectVersion.name != null && projectVersion.name.toLowerCase().indexOf(VERSION_NAME_PREFIX.toLowerCase()) > -1){
        verName = projectVersion.name.replaceAll("${VERSION_NAME_PREFIX}", "")
            try {
                // println("${projectKey}. verName ${verName}")
                projectsSet.add(Integer.parseInt(verName))
            } catch(Exception e) {}
        }
    }
    latestVersion = mostRecentOldVersion(projectsSet, VERSION_LATEST)
    if (latestVersion != null){
        try {
            new SemVer("${latestVersion}")
        } catch (IllegalArgumentException ex){
            latestVersion = "${VERSION_NAME_PREFIX}${DEFAULT_VERSION}"
        }
    }
    println("Getting current versions of project's ${projectKey}. Biggest version is ${latestVersion} : ${projectsSet}")
    return latestVersion
}

def prepareNewVersions(String projectKey, String currVersion) {
    def projectVersionsSet = new HashSet()
    projectVersionsMap = [].withDefault{ [:] }
    def minVersion = versionsSet.min()
    currVersion = currVersion.replaceAll("${VERSION_NAME_PREFIX}", "")

    try {
        version =  new SemVer("${currVersion}")
        for(int i = 0; i < numberOfVersionsToCreate; i++){
        println("i=${i} - numberOfVersionsToCreate=${numberOfVersionsToCreate}")
            version = version.bump(VERSION_INCREASE_TYPE)
            // println("projectKey-${projectKey}: ${VERSION_NAME_PREFIX}${version.toString()}")
            projectVersionsSet.add("${VERSION_NAME_PREFIX}${version.toString()}")
        }
    } catch (Exception ex){
        println("ERROR when prepareNewVersions ${currVersion} on ${projectKey}. Exception: ${ex.getMessage()}")
    }
    
    println("Preparing project's ${projectKey} new versions: ${projectVersionsSet}")
    return projectVersionsSet
}

def createNewVersionsInJira(String projectKey, String versionName) {
    API_URL="https://powerupai.atlassian.net/rest/api/3/version"
    def projectsSet = new HashSet()
    println("Creating version ${versionName} in project ${projectKey}")
    API_PARAMS=[Authorization: "${API_TOKEN}", Accept: 'application/json']
    def JiraResponse = new RESTClient("${API_URL}")
    JiraResponse.setHeaders(API_PARAMS)
    def requestBody = "{\"name\": \"${versionName}\", \"projectId\": ${projectKey.split("_")[0]}}"

    println("API_URL=${API_URL}")
    println("API_PARAMS=${API_PARAMS}")
    println("requestBody=${requestBody}")
    try {
        def resp = JiraResponse.post("contentType": "application/json", "body": "${requestBody}")
        def JiraResponseData = resp.getData()
        println("JiraResponseData=${JiraResponseData}")
        if (resp.status == 201) {
            println("Version ${versionName} created on ${projectKey} SUCCESSFULLY")
        } else {
            println("ERROR when creating version ${versionName} on ${projectKey}. Error code: ${resp.status}")
            println("${JiraResponseData}")
        }
    } catch (Exception e) {
        println("ERROR when creating version ${versionName} on ${projectKey}. Exception: ${e.getMessage()}")
    }

}

def mostRecentOldVersion(versions, recentOld){
    return versions.collectEntries{ 
        [(it=~/\d+|\D+/).findAll().collect{it.padLeft(3,'0')}.join(),it]
    }.sort().values()[recentOld]
}

def testProjectsVersions() {
    testVersionsSet = new HashSet()
    testVersionsSet.add("${VERSION_NAME_PREFIX}1.1.2")
    testVersionsSet.add("${VERSION_NAME_PREFIX}0.0.1")
    testVersionsSet.add("${VERSION_NAME_PREFIX}1.0.0")
    testVersionsSet.add("${VERSION_NAME_PREFIX}1.0.2")
    testVersionsSet.add("${VERSION_NAME_PREFIX}1.1.1")
    testVersionsSet.add("${VERSION_NAME_PREFIX}1.0.1")
    testVersionsSet.add("${VERSION_NAME_PREFIX}0.0.2")
    testVersionsSet.each { projectVersion ->
        println("${projectVersion}")
    }
    
    latestVer=mostRecentOldVersion(testVersionsSet, VERSION_LATEST)
    println("latestVer = ${latestVer}")

    lowestVersion=mostRecentOldVersion(testVersionsSet, VERSION_OLDEST)
    println("lowestVersion = ${lowestVersion}")
    
}

// testProjectsVersions()
// get list of Jira Projects & their versions
getProjectsList().each { projectKey ->
    projectMaxVersion = getProjectsVersions(projectKey)
    if(projectMaxVersion != null){
        println("versionsMapByProject ${projectKey} - ${projectMaxVersion}")
        versionsMapByProject[projectKey] = projectMaxVersion
        versionsSet.addAll(projectMaxVersion)
    }
} 
// // prepare the new Jira version names
versionsMapByProject.each {key, value ->
    versionsMapByProject[key] = prepareNewVersions(key, value)
}
// // create the new versions in Jira
versionsMapByProject.each {key, value ->
    println("SECOND versionsMapByProject ${key} - ${value}")
    // createNewVersionsInJira(key, value)
    value.each {versionName ->
        createNewVersionsInJira(key, versionName)
    }
}
