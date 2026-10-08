package com.neojou.mystudy

/**
 * Product name and version shown by the app.
 *
 * [APP_NAME] is the user-visible name. The window title, the HTML title, the home
 * headline, About, and the macOS application menu all read it.
 * `./configure.sh proj_name` rewrites the literal on that line.
 * See docs/programming/How-To-Change_Project_Name.md.
 *
 * [NAME] is the product version, with no leading `v`. About shows `Version` plus
 * this value. The Gradle project version reads `app.version` in gradle.properties.
 * `./configure.sh version` rewrites both.
 * See docs/programming/How-To-Change_Version_number.md.
 */
object AppVersion {
    /**
     * User-visible product name.
     *
     * `./configure.sh proj_name` rewrites the literal on this line.
     */
    const val APP_NAME: String = "mystudy" // configure:app.displayName

    /**
     * Product version, for example `0.1` or `0.2.0`. No leading `v`.
     *
     * `./configure.sh version` rewrites the literal on this line.
     */
    const val NAME: String = "0.2" // configure:app.version
}
