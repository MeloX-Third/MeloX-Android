package com.lladlam.melox.core.music

import com.lladlam.melox.core.music.model.MusicPage
import com.lladlam.melox.core.music.model.MusicResourceId
import com.lladlam.melox.core.music.model.MusicSource
import com.lladlam.melox.core.music.model.MusicTrack
import com.lladlam.melox.core.music.provider.MusicCapability
import com.lladlam.melox.core.music.provider.MusicProvider
import com.lladlam.melox.core.music.provider.MusicProviderRegistry
import com.lladlam.melox.core.music.provider.SearchCapability
import com.lladlam.melox.core.music.provider.UnifiedMusicService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedMusicServiceTest {
    @Test fun dynamicWhitelistPreservesPaginationAndUnavailableIdentity() = runBlocking {
        val dynamic = com.lladlam.melox.core.music.model.SourceId.parse("plugin:org.example.search")!!
        val missing = com.lladlam.melox.core.music.model.SourceId.parse("plugin:org.example.missing")!!
        val registry = MusicProviderRegistry(listOf(FakeSearchProvider(MusicSource.Netease, mutableListOf())))
        val plugin = object : MusicProvider, SearchCapability {
            override val sourceId = dynamic
            override val displayName = "Dynamic"
            override val capabilities = setOf(MusicCapability.Search)
            override suspend fun searchSongs(query: String, page: Int, pageSize: Int) = MusicPage(
                listOf(MusicTrack(MusicResourceId(dynamic, "opaque"), query, emptyList())), page, pageSize, 101L, true)
        }
        registry.registerDynamic(plugin)
        val service = UnifiedMusicService(registry)
        val result = service.searchSourceIds("test", setOf(dynamic, missing), 2, 25)
        assertEquals(dynamic, result.tracks.single().id.sourceId)
        assertEquals(2, result.pages[dynamic]?.page)
        assertEquals(101L, result.pages[dynamic]?.total)
        assertEquals(missing, result.failures.single().sourceId)
        registry.unregisterDynamic(dynamic)
        val unavailable = service.searchSourceIds("test", setOf(dynamic))
        assertTrue(unavailable.tracks.isEmpty())
        assertEquals(dynamic, unavailable.failures.single().sourceId)
    }

    @Test fun cancelledProviderCallIsNotConvertedToPartialSuccess() {
        val provider = object : MusicProvider, SearchCapability {
            override val source = MusicSource.QQMusic
            override val displayName = "Cancelled"
            override val capabilities = setOf(MusicCapability.Search)
            override suspend fun searchSongs(query: String, page: Int, pageSize: Int): MusicPage<MusicTrack> {
                throw kotlinx.coroutines.CancellationException("cancelled")
            }
        }
        org.junit.Assert.assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            runBlocking { UnifiedMusicService(MusicProviderRegistry(listOf(provider))).searchSongs("test", setOf(MusicSource.QQMusic)) }
        }
    }
    @Test
    fun onlyExplicitlyWhitelistedProvidersAreQueried() = runBlocking {
        val calls = mutableListOf<MusicSource>()
        val netease = FakeSearchProvider(MusicSource.Netease, calls)
        val qq = FakeSearchProvider(MusicSource.QQMusic, calls)
        val kugou = FakeSearchProvider(MusicSource.Kugou, calls)
        val service = UnifiedMusicService(MusicProviderRegistry(listOf(netease, qq, kugou)))

        val result = service.searchSongs(
            query = "test",
            sources = setOf(MusicSource.QQMusic),
        )

        assertEquals(listOf(MusicSource.QQMusic), calls)
        assertEquals(1, result.tracks.size)
        assertEquals(MusicSource.QQMusic, result.tracks.single().id.source)
    }

    @Test
    fun providerFailureDoesNotEraseSuccessfulProviderResults() = runBlocking {
        val good = FakeSearchProvider(MusicSource.QQMusic, mutableListOf())
        val broken = object : MusicProvider, SearchCapability {
            override val source = MusicSource.Kugou
            override val displayName = source.displayName
            override val capabilities = setOf(MusicCapability.Search)
            override suspend fun searchSongs(query: String, page: Int, pageSize: Int): MusicPage<MusicTrack> {
                error("simulated failure")
            }
        }
        val service = UnifiedMusicService(MusicProviderRegistry(listOf(good, broken)))

        val result = service.searchSongs(
            query = "test",
            sources = setOf(MusicSource.QQMusic, MusicSource.Kugou),
        )

        assertEquals(1, result.tracks.size)
        assertEquals(MusicSource.QQMusic, result.tracks.single().id.source)
        assertEquals(1, result.failures.size)
        assertEquals(MusicSource.Kugou, result.failures.single().source)
        assertTrue(result.failures.single().message.contains("simulated failure"))
    }

    @Test fun aggregationUsesExplicitIdentitiesAndRejectsForeignResults() = runBlocking {
        val id = com.lladlam.melox.core.music.model.SourceId.parse("plugin:org.example.aggregate")!!
        val registry = MusicProviderRegistry(listOf(FakeSearchProvider(MusicSource.Netease, mutableListOf())))
        val correct = MusicTrack(MusicResourceId(id, "1"), "Test", emptyList())
        var track = correct
        val plugin = object : MusicProvider, com.lladlam.melox.core.music.provider.LocalAggregationCapability {
            override val sourceId = id
            override val displayName = "Aggregation"
            override val capabilities = emptySet<MusicCapability>()
            override suspend fun aggregationTracks(page: Int, pageSize: Int) = MusicPage(listOf(track), page, pageSize)
        }
        registry.registerDynamic(plugin)
        val service = UnifiedMusicService(registry)
        assertTrue(service.collectAggregationTracks(emptySet()).tracks.isEmpty())
        assertEquals(listOf(correct), service.collectAggregationTracks(setOf(id)).tracks)
        track = correct.copy(id = MusicResourceId(MusicSource.Netease, "1"))
        val rejected = service.collectAggregationTracks(setOf(id))
        assertTrue(rejected.tracks.isEmpty())
        assertEquals(id, rejected.failures.single().sourceId)
    }

    @Test fun playbackCacheKeyTracksCurrentScopeWithoutExposingIt() {
        val id = com.lladlam.melox.core.music.model.SourceId.fromBuiltIn(MusicSource.Netease)
        var scope = "first-session"
        val registry = MusicProviderRegistry(
            listOf(FakeSearchProvider(MusicSource.Netease, mutableListOf())),
            playbackCacheScopes = mapOf(id to { scope }),
        )
        val original = registry.playbackCacheKey(id)
        assertTrue(original.matches(Regex("[a-f0-9]{64}")))
        scope = "second-session"
        assertTrue(original != registry.playbackCacheKey(id))
        assertEquals("", registry.playbackCacheKey(com.lladlam.melox.core.music.model.SourceId.fromBuiltIn(MusicSource.QQMusic)))
    }

    @Test fun registrySupportsAPluginWithoutBuiltInSources() {
        val registry = MusicProviderRegistry(emptyList())
        val id = com.lladlam.melox.core.music.model.SourceId.parse("plugin:org.example.only-source")!!
        val provider = object : MusicProvider {
            override val sourceId = id
            override val displayName = "Only source"
            override val capabilities = emptySet<MusicCapability>()
        }
        assertTrue(registry.providers.isEmpty())
        registry.registerDynamic(provider)
        assertEquals(provider, registry.require(id))
        assertTrue(registry.unregisterDynamic(id))
        assertEquals(null, registry[id])
    }

    @Test fun registryRejectsMissingPlaylistImplementationDuringRegistrationAndReplacement() {
        val registry = MusicProviderRegistry(emptyList())
        val id = com.lladlam.melox.core.music.model.SourceId.parse("plugin:org.example.playlists")!!
        fun provider(vararg declared: MusicCapability) = object : MusicProvider {
            override val sourceId = id
            override val displayName = "Playlist source"
            override val capabilities = declared.toSet()
        }
        val invalid = provider(MusicCapability.PlaylistReorder)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { registry.registerDynamic(invalid) }
        assertEquals(null, registry[id])
        val active = provider()
        registry.registerDynamic(active)
        val generation = registry.generation
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { registry.replaceDynamic(invalid) }
        assertEquals(active, registry[id])
        assertEquals(generation, registry.generation)
    }

    private class FakeSearchProvider(
        override val source: MusicSource,
        private val calls: MutableList<MusicSource>,
    ) : MusicProvider, SearchCapability {
        override val displayName = source.displayName
        override val capabilities = setOf(MusicCapability.Search)

        override suspend fun searchSongs(query: String, page: Int, pageSize: Int): MusicPage<MusicTrack> {
            calls += source
            return MusicPage(
                items = listOf(
                    MusicTrack(
                        id = MusicResourceId(source, "${source.storageValue}-track"),
                        title = query,
                        artists = emptyList(),
                    ),
                ),
                page = page,
                pageSize = pageSize,
                total = 1,
            )
        }
    }
}
