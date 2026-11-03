version = 2

cloudstream {
    language = "hi"
    // All of these properties are optional, you can safely remove them

    description = "VegaMovies + RogMovies"
    authors = listOf("Akshat-Jain737")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "TvSeries",
        "Movie",
        "AsianDrama",
        "Anime"
    )

    // After you push this to your own GitHub repo, replace with:
    // https://raw.githubusercontent.com/<you>/<repo>/master/VegaMovies/icon.jpg
    iconUrl = "https://github.com/SaurabhKaperwan/CSX/raw/refs/heads/master/VegaMovies/icon.jpg"
}
