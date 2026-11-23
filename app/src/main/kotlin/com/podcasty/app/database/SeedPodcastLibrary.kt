package com.podcasty.app.database

object SeedPodcastLibrary {
    val defaultDownloadedEpisodeIds: Set<String> = emptySet()

    fun buildSeed(nowMs: Long = System.currentTimeMillis()): SeedBundle = SeedBundle(
        categories = emptyList(),
        hosts = emptyList(),
        podcasts = emptyList(),
        episodes = emptyList(),
        collections = emptyList(),
        collectionEpisodes = emptyList(),
        history = emptyList(),
    )
}

data class SeedBundle(
    val categories: List<CategoryEntity>,
    val hosts: List<HostEntity>,
    val podcasts: List<PodcastEntity>,
    val episodes: List<EpisodeEntity>,
    val collections: List<CollectionEntity>,
    val collectionEpisodes: List<CollectionEpisodeEntity>,
    val history: List<ListeningHistoryEntity>,
)
