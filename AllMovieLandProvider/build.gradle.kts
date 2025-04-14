// use an integer for version numbers
version = 12


cloudstream {
    // All of these properties are optional, you can safely remove them

    description = "Please wait for 10seconds to bypass the ads"
    language = "hi"
    authors = listOf("darkdemon, likdev256")

    /**
    * Status int as the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta only
    * */
    status = 1 // will be 3 if unspecified

    // List of video source types. Users are able to filter for extensions in a given category.
    // You can find a list of avaliable types here:
    // https://recloudstream.github.io/cloudstream/html/app/com.lagradost.cloudstream3/-tv-type/index.html
    tvTypes = listOf(
        "TvSeries",
        "Movie",
        "Anime",
        "Cartoon"
    )
    iconUrl = "https://www.google.com/s2/favicons?domain=allmoviesland.com&sz=%size%"
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs = listOf("-Xdeprecation-is-not-an-error")
    }
}
