package com.lladlam.melox.ui.search

import androidx.compose.runtime.saveable.rememberSaveable
import com.lladlam.melox.ui.state.rememberRetainedPageState

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lladlam.melox.core.music.model.*
import com.lladlam.melox.core.music.provider.*
import com.lladlam.melox.core.plugin.PluginErrors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.lladlam.melox.R
import com.lladlam.melox.ui.library.MeloXProviderPlaylistSyncSheet

/** Source-neutral collection navigation inside the existing Search screen. */
@Composable
internal fun PluginCatalogBrowser(
    registry: MusicProviderRegistry,
    sourceValue: String,
    query: String,
    generation: Long,
    initialSection: String = "songs",
    trackContent: @Composable (List<MusicTrack>) -> Unit,
) {
    val provider = SourceId.parse(sourceValue)?.let { registry[it] }
    key(sourceValue, generation) {
        PluginCatalogContent(registry, sourceValue, query, generation, initialSection, provider, trackContent)
    }
}

@Composable
private fun PluginCatalogContent(
    registry: MusicProviderRegistry,
    sourceValue: String,
    query: String,
    generation: Long,
    initialSection: String,
    provider: MusicProvider?,
    trackContent: @Composable (List<MusicTrack>) -> Unit,
) {
    var section by rememberSaveable(sourceValue, initialSection) { mutableStateOf(initialSection) }
    var collection by rememberRetainedPageState(sourceValue, section, query) { mutableStateOf<Any?>(null) }
    var page by rememberSaveable(sourceValue, section, query, collection) { mutableIntStateOf(1) }
    var rows by rememberRetainedPageState(sourceValue) { mutableStateOf<List<Any>>(emptyList()) }
    var tracks by rememberRetainedPageState(sourceValue) { mutableStateOf<List<MusicTrack>>(emptyList()) }
    var hasMore by rememberRetainedPageState(sourceValue) { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var accountName by rememberSaveable(sourceValue) { mutableStateOf<String?>(null) }
    var loadedRequest by rememberRetainedPageState(sourceValue) { mutableStateOf<List<Any?>?>(null) }
    val scope = rememberCoroutineScope()
    var revision by rememberSaveable(sourceValue, generation) { mutableIntStateOf(0) }
    var editablePlaylist by rememberRetainedPageState(sourceValue, generation, collection, page, revision) { mutableStateOf<MusicPlaylistSummary?>(null) }
    var members by rememberRetainedPageState(sourceValue, generation, collection, page, revision) { mutableStateOf<List<Pair<String, MusicTrack>>>(emptyList()) }
    var reorderOpen by remember(collection, page) { mutableStateOf(false) }
    var reorderError by remember(collection, page) { mutableStateOf<String?>(null) }
    var removeOpen by remember(collection, page) { mutableStateOf(false) }
    var removeError by remember(collection, page) { mutableStateOf<String?>(null) }
    var deleteOpen by remember(collection, page) { mutableStateOf(false) }
    var deleteError by remember(collection, page) { mutableStateOf<String?>(null) }
    var renameOpen by remember(collection, page) { mutableStateOf(false) }
    var mutationBusy by remember { mutableStateOf(false) }
    var renameError by remember(collection, page) { mutableStateOf<String?>(null) }
    val search = (provider as? SearchCapability)?.takeIf { provider.supports(MusicCapability.Search) }
    val catalogSearch = provider as? CatalogSearchCapability
    val playlists = (provider as? PlaylistCatalogCapability)?.takeIf { provider.supports(MusicCapability.Playlists) }
    val albums = (provider as? AlbumCapability)?.takeIf { provider.supports(MusicCapability.Albums) }
    val artists = (provider as? ArtistCapability)?.takeIf { provider.supports(MusicCapability.Artists) }
    val rankings = (provider as? RankingCatalogCapability)?.takeIf { provider.supports(MusicCapability.Rankings) }
    val home = (provider as? HomeFeedCapability)?.takeIf { provider.supports(MusicCapability.HomeRecommendations) }
    val library = (provider as? UserLibraryCapability)?.takeIf { provider.supports(MusicCapability.Library) }
    val collections = (provider as? LibraryCollectionCapability)?.takeIf { provider.supports(MusicCapability.Library) }
    var createOpen by remember(section, collection) { mutableStateOf(false) }
    var createName by remember(section, collection) { mutableStateOf("") }
    var createError by remember(section, collection) { mutableStateOf<String?>(null) }
    val create = (provider as? PlaylistCreateCapability)?.takeIf { provider.supports(MusicCapability.PlaylistCreate) }
    val rename = (provider as? PlaylistRenameCapability)?.takeIf { provider.supports(MusicCapability.PlaylistRename) }
    val delete = (provider as? PlaylistDeleteCapability)?.takeIf { provider.supports(MusicCapability.PlaylistDelete) }
    val remove = (provider as? PlaylistRemoveMemberCapability)?.takeIf { provider.supports(MusicCapability.PlaylistRemoveMember) }
    val reorder = (provider as? PlaylistReorderCapability)?.takeIf { provider.supports(MusicCapability.PlaylistReorder) }
    val sections = buildList {
        if (search != null) add("songs" to "歌曲")
        if (playlists != null && (query.isBlank() || catalogSearch != null)) add("playlists" to "歌单")
        if (albums != null && catalogSearch != null) add("albums" to "专辑")
        if (artists != null && catalogSearch != null) add("artists" to "歌手")
        if (rankings != null) add("rankings" to "榜单")
        if (home != null) add("home" to "推荐")
        if (library != null) add("library" to "我的歌单")
        if (collections != null) {
            add("savedAlbums" to "收藏专辑")
            add("followedArtists" to "关注歌手")
        }
    }
    LaunchedEffect(sourceValue, generation, sections) {
        if (sections.none { it.first == section }) section = sections.firstOrNull()?.first ?: "songs"
    }
    LaunchedEffect(sourceValue, query, generation, section, collection, page, revision) {
        val requestKey = listOf(sourceValue, query, generation, section, collection, page, revision)
        if (loadedRequest == requestKey) return@LaunchedEffect
        loadedRequest = null
        editablePlaylist = null
        members = emptyList()
        rows = emptyList(); tracks = emptyList(); error = null; hasMore = false; accountName = null
        if (provider == null) { error = "音源已禁用或卸载：$sourceValue"; return@LaunchedEffect }
        if (sections.none { it.first == section }) return@LaunchedEffect
        loading = true
        try {
            delay(250)
            val size = 30
            fun <T> apply(result: MusicPage<T>) {
                hasMore = result.hasMore
                val values = result.items
                if (values.all { it is MusicTrack }) tracks = values.filterIsInstance<MusicTrack>()
                else rows = values.filterNotNull()
            }
            when (val selected = collection) {
                is MusicPlaylistSummary -> requireNotNull(provider as? PlaylistCapability).playlistDetail(selected, page, size).let {
                    members = it.memberIds?.zip(it.tracks).orEmpty()
                    editablePlaylist = it.summary.takeIf { summary -> summary.isEditable == true }
                    tracks = it.tracks; hasMore = it.hasMore ?: (it.total?.let { total -> page.toLong() * size < total } ?: (tracks.size == size))
                }
                is MusicAlbumSummary -> requireNotNull(provider as? AlbumCapability).albumDetail(selected, page, size).let {
                    tracks = it.tracks; hasMore = it.hasMore ?: (it.totalTracks?.let { total -> page.toLong() * size < total } ?: (tracks.size == size))
                }
                is MusicArtistSummary -> requireNotNull(provider as? ArtistCapability).artistDetail(selected, page, size).let {
                    tracks = it.tracks; hasMore = it.hasMore ?: (it.totalTracks?.let { total -> page.toLong() * size < total } ?: (tracks.size == size))
                }
                is MusicRankingSummary -> apply(requireNotNull(provider as? RankingCapability).rankingTracks(selected, page, size))
                else -> when (section) {
                    "songs" -> if (query.isNotBlank()) apply(requireNotNull(search).searchSongs(query.trim(), page, size))
                    "playlists" -> apply(if (query.isBlank()) requireNotNull(playlists).playlists(page, size) else requireNotNull(catalogSearch).searchPlaylists(query.trim(), page, size))
                    "albums" -> apply(requireNotNull(catalogSearch).searchAlbums(query.trim(), page, size))
                    "artists" -> apply(requireNotNull(catalogSearch).searchArtists(query.trim(), page, size))
                    "rankings" -> apply(requireNotNull(rankings).rankings(page, size))
                    "home" -> requireNotNull(home).homeFeed(30, 30, 30).let { rows = it.recommendedPlaylists + it.rankings; tracks = it.newSongs }
                    "library" -> { accountName = requireNotNull(library).accountSummary()?.displayName; apply(requireNotNull(library).userPlaylists(page, size)) }
                    "savedAlbums" -> apply(requireNotNull(collections).savedAlbums(page, size))
                    "followedArtists" -> apply(requireNotNull(collections).followedArtists(page, size))
                }
            }
            if (registry.generation != generation || registry[provider.sourceId] !== provider) {
                rows = emptyList(); tracks = emptyList(); error = "音源状态已变化"
            }
            if (error == null) loadedRequest = requestKey
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = com.lladlam.melox.core.plugin.PluginErrors.describe(failure) }
        finally { loading = false }
    }
    Column(Modifier.fillMaxSize()) {
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
            items(sections) { (key, label) -> TextButton(onClick = { section = key }) { Text(if (section == key) "✓ $label" else label) } }
        }
        if (collection != null) TextButton(onClick = { collection = null }) { Text("返回列表") }
        if (section == "library" && collection == null && provider != null && create != null) {
            TextButton(enabled = !mutationBusy, onClick = {
                createName = ""
                createError = null
                createOpen = true
            }) { Text(stringResource(R.string.player_create_playlist)) }
            if (createOpen) AlertDialog(
                onDismissRequest = { if (!mutationBusy) createOpen = false },
                title = { Text(stringResource(R.string.player_new_playlist)) },
                text = {
                    Column {
                        OutlinedTextField(
                            value = createName,
                            onValueChange = { createName = it },
                            enabled = !mutationBusy,
                            singleLine = true,
                            label = { Text(stringResource(R.string.player_playlist_name)) },
                        )
                        createError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                },
                confirmButton = {
                    TextButton(enabled = !mutationBusy && createName.isNotBlank(), onClick = {
                        if (!mutationBusy && registry[provider.sourceId] === provider) {
                            val name = createName.trim()
                            mutationBusy = true
                            createError = null
                            scope.launch {
                                try {
                                    create.createPlaylist(name)
                                    createOpen = false
                                    page = 1
                                    revision++
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (failure: Exception) { createError = PluginErrors.describe(failure) }
                                finally { mutationBusy = false }
                            }
                        }
                    }) { Text(stringResource(R.string.player_create_playlist)) }
                },
                dismissButton = {
                    TextButton(enabled = !mutationBusy, onClick = { createOpen = false }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                },
            )
        }
        val playlist = editablePlaylist
        if (playlist != null && provider != null && !loading && error == null) {
            if (rename != null) TextButton(onClick = { renameError = null; renameOpen = true }) {
                Text(stringResource(R.string.player_rename_playlist))
            }
            if (delete != null) {
                TextButton(enabled = !mutationBusy, onClick = { deleteError = null; deleteOpen = true }) {
                    Text(stringResource(R.string.player_delete_playlist))
                }
                if (deleteOpen) AlertDialog(
                    onDismissRequest = { if (!mutationBusy) deleteOpen = false },
                    title = { Text(stringResource(R.string.player_delete_playlist)) },
                    text = { Text(deleteError ?: playlist.title) },
                    confirmButton = {
                        TextButton(enabled = !mutationBusy, onClick = {
                            if (!mutationBusy && registry[provider.sourceId] === provider) {
                                mutationBusy = true
                                deleteError = null
                                scope.launch {
                                    try {
                                        delete.deletePlaylist(playlist)
                                        if ((collection as? MusicPlaylistSummary)?.id == playlist.id) collection = null
                                        revision++
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (failure: Exception) { deleteError = PluginErrors.describe(failure) }
                                    finally { mutationBusy = false }
                                }
                            }
                        }) { Text(stringResource(R.string.player_delete_playlist)) }
                    },
                    dismissButton = {
                        TextButton(enabled = !mutationBusy, onClick = { deleteOpen = false }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    },
                )
            }
            if (remove != null && members.isNotEmpty()) {
                TextButton(enabled = !mutationBusy, onClick = { removeError = null; removeOpen = true }) {
                    Text(stringResource(R.string.player_remove_from_playlist))
                }
                if (removeOpen) AlertDialog(
                    onDismissRequest = { if (!mutationBusy) removeOpen = false },
                    title = { Text(stringResource(R.string.player_remove_from_playlist)) },
                    text = {
                        LazyColumn(Modifier.heightIn(max = 360.dp)) {
                            removeError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
                            items(members, key = { it.first }) { (memberId, track) ->
                                TextButton(enabled = !mutationBusy, onClick = {
                                    if (!mutationBusy && registry[provider.sourceId] === provider) {
                                        mutationBusy = true
                                        removeError = null
                                        scope.launch {
                                            try {
                                                remove.removePlaylistMember(playlist, memberId)
                                                removeOpen = false
                                                if (members.size == 1 && page > 1 && !hasMore) page--
                                                revision++
                                            } catch (cancelled: CancellationException) { throw cancelled }
                                            catch (failure: Exception) { removeError = PluginErrors.describe(failure) }
                                            finally { mutationBusy = false }
                                        }
                                    }
                                }) { Text(track.title) }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(enabled = !mutationBusy, onClick = { removeOpen = false }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    },
                )
            }
            if (reorder != null && members.size > 1) {
                TextButton(enabled = !mutationBusy, onClick = { reorderError = null; reorderOpen = true }) {
                    Text(stringResource(R.string.plugin_playlist_reorder))
                }
                if (reorderOpen) AlertDialog(
                    onDismissRequest = { if (!mutationBusy) reorderOpen = false },
                    title = { Text(stringResource(R.string.plugin_playlist_reorder)) },
                    text = {
                        LazyColumn(Modifier.heightIn(max = 360.dp)) {
                            reorderError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
                            itemsIndexed(members, key = { _, member -> member.first }) { index, (memberId, track) ->
                                fun move(offset: Int) {
                                    val anchorId = members.getOrNull(index + offset)?.first ?: return
                                    if (mutationBusy || registry[provider.sourceId] !== provider) return
                                    mutationBusy = true
                                    reorderError = null
                                    scope.launch {
                                        try {
                                            reorder.movePlaylistMember(playlist, memberId, anchorId, before = offset < 0)
                                            reorderOpen = false
                                            revision++
                                        } catch (cancelled: CancellationException) { throw cancelled }
                                        catch (failure: Exception) { reorderError = PluginErrors.describe(failure) }
                                        finally { mutationBusy = false }
                                    }
                                }
                                Column {
                                    Text(track.title)
                                    Row {
                                        TextButton(enabled = !mutationBusy && index > 0, onClick = { move(-1) }) {
                                            Text(stringResource(R.string.player_move_up))
                                        }
                                        TextButton(enabled = !mutationBusy && index < members.lastIndex, onClick = { move(1) }) {
                                            Text(stringResource(R.string.player_move_down))
                                        }
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(enabled = !mutationBusy, onClick = { reorderOpen = false }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    },
                )
            }
            if (renameOpen && rename != null) MeloXProviderPlaylistSyncSheet(
                title = playlist.title, trackName = null, canRename = true, canDelete = false,
                canRemove = false, canMoveUp = false, canMoveDown = false,
                busy = mutationBusy, error = renameError,
                onRename = { name ->
                    if (!mutationBusy && registry[provider.sourceId] === provider) {
                        mutationBusy = true
                        renameError = null
                        scope.launch {
                            try {
                                rename.renamePlaylist(playlist, name)
                                renameOpen = false
                                revision++
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { renameError = PluginErrors.describe(failure) }
                            finally { mutationBusy = false }
                        }
                    }
                },
                onDelete = {}, onRemove = {}, onMoveUp = {}, onMoveDown = {},
                onDismiss = { if (!mutationBusy) renameOpen = false },
            )
        }
        accountName?.let { Text(it, Modifier.padding(horizontal = 20.dp)) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(enabled = page > 1 && !loading, onClick = { page-- }) { Text("上一页") }
            Text("第 $page 页", Modifier.padding(12.dp))
            TextButton(enabled = hasMore && !loading, onClick = { page++ }) { Text("下一页") }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error) }
        if (rows.isNotEmpty()) LazyColumn(Modifier.weight(if (tracks.isEmpty()) 1f else .45f)) {
            items(rows) { value ->
                val title = when (value) {
                        is MusicPlaylistSummary -> "歌单 · ${value.title}"
                        is MusicAlbumSummary -> "专辑 · ${value.title}"
                        is MusicArtistSummary -> "歌手 · ${value.name}"
                        is MusicRankingSummary -> "榜单 · ${value.title}"
                        else -> ""
                }
                val artwork = when (value) {
                    is MusicPlaylistSummary -> value.artworkUrl
                    is MusicAlbumSummary -> value.artworkUrl
                    is MusicArtistSummary -> value.artworkUrl
                    is MusicRankingSummary -> value.artworkUrl
                    else -> null
                }
                com.lladlam.melox.ui.glass.MeloXIosListRow(title = title, onClick = { collection = value }, leading = {
                    if (artwork != null) coil3.compose.AsyncImage(model = artwork, contentDescription = null, modifier = Modifier.size(44.dp))
                })
            }
        }
        if (!loading && error == null && rows.isEmpty() && tracks.isEmpty()) Text("暂无内容", Modifier.padding(20.dp))
        if (tracks.isNotEmpty()) Box(Modifier.weight(1f)) { trackContent(tracks) }
    }
}
