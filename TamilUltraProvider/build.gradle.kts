// use an integer for version numbers
version = 5

android {
    kotlinOptions {
        freeCompilerArgs = freeCompilerArgs + "-Xdeprecation-is-not-an-error"
    }
}

cloudstream {
    // All of these properties are optional, you can safely remove them

    description = "Indian Live TV Provider - Enhanced stream detection and quality selection"
    language = "ta"
    authors = listOf("LikDev-256")

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
        "Live",
    )
    iconUrl = "https://raw.githubusercontent.com/LikDev-256/likdev256-tamil-providers/master/TamilUltraProvider/icon.png"
}
