import { definePlugin, PluginError, type HostContext, type MusicPage, type MusicTrack } from "../../src/index.js";
import tone from "../test-tone/index.js";

// An owned, synthetic CC0 catalog, not an emulation of any third-party service.
const track: MusicTrack = { id: "tone-440hz", title: "MeloX 440 Hz Test Tone", artists: [{ id: "author", name: "MeloX SDK Example" }], durationMs: 250 };
const playlist = { id: "signals", title: "CC0 Test Signals", creatorName: "MeloX SDK", trackCount: 1 };
const writable = { id: "saved-signals", title: "Saved Test Signals", creatorName: "MeloX SDK" };
const savedSignalKey = "playlist.saved-signals.tone-440hz";
const album = { id: "signal-album", title: "Test Signals", artists: track.artists, trackCount: 1 };
const artist = { id: "author", name: "MeloX SDK Example", songCount: 1, albumCount: 1 };
const ranking = { id: "signal-ranking", title: "Test Signal Ranking" };
const pageOf = <T>(values: T[], page: number, pageSize: number): MusicPage<T> => ({
  items: values.slice((page - 1) * pageSize, page * pageSize), page, pageSize,
  total: values.length, hasMore: page * pageSize < values.length,
});
const checkId = (actual: string, expected: string) => { if (actual !== expected) throw new PluginError("NOT_FOUND"); };

type EditablePlaylist = { id: string; title: string; members: string[] };
type Library = { nextId: number; playlists: EditablePlaylist[] };
const libraryKey = "playlist.library.v2";
async function readLibrary(host: HostContext): Promise<Library> {
  const stored = await host.storage.get(libraryKey);
  if (stored !== null) return JSON.parse(stored) as Library;
  const hadSignal = await host.storage.get(savedSignalKey) === "true";
  return { nextId: hadSignal ? 2 : 1, playlists: [{ id: writable.id, title: writable.title, members: hadSignal ? ["member-1"] : [] }] };
}
const saveLibrary = (host: HostContext, library: Library) => host.storage.set(libraryKey, JSON.stringify(library));
const summary = (entry: EditablePlaylist) => ({ id: entry.id, title: entry.title, trackCount: entry.members.length, isEditable: true });
function owned(library: Library, id: string): EditablePlaylist {
  const entry = library.playlists.find(item => item.id === id);
  if (!entry) throw new PluginError("NOT_FOUND");
  return entry;
}
function memberIndex(entry: EditablePlaylist, id: string): number {
  const index = entry.members.indexOf(id);
  if (index < 0) throw new PluginError("NOT_FOUND");
  return index;
}

export default definePlugin({
  ...tone,
  async resolveDownload(requested) { return tone.resolvePlayback(requested); },
  async playlists(page, pageSize) { return pageOf([playlist], page, pageSize); },
  async searchPlaylists(query, page, pageSize) { return pageOf(playlist.title.toLowerCase().includes(query.toLowerCase()) ? [playlist] : [], page, pageSize); },
  async playlistDetail(id, page, pageSize, host) {
    if (id === playlist.id) return { summary: playlist, tracks: pageOf([track], page, pageSize) };
    const entry = owned(await readLibrary(host), id);
    const members = pageOf(entry.members, page, pageSize);
    return { summary: summary(entry), tracks: { ...members, items: members.items.map(() => track) }, memberIds: members.items };
  },
  async writablePlaylists(page, pageSize, host) {
    return pageOf((await readLibrary(host)).playlists.map(summary), page, pageSize);
  },
  async createPlaylist(name, host) {
    const library = await readLibrary(host);
    const entry = { id: `playlist-${library.nextId++}`, title: name, members: [] };
    library.playlists.push(entry);
    await saveLibrary(host, library);
    return summary(entry);
  },
  async addTrackToPlaylist(requested, playlistId, host) {
    checkId(requested.id, track.id);
    const library = await readLibrary(host);
    owned(library, playlistId).members.push(`member-${library.nextId++}`);
    await saveLibrary(host, library);
  },
  async renamePlaylist(playlistId, name, host) {
    const library = await readLibrary(host);
    owned(library, playlistId).title = name;
    await saveLibrary(host, library);
  },
  async deletePlaylist(playlistId, host) {
    const library = await readLibrary(host);
    const entry = owned(library, playlistId);
    library.playlists = library.playlists.filter(item => item !== entry);
    await saveLibrary(host, library);
  },
  async removePlaylistMember(playlistId, memberId, host) {
    const library = await readLibrary(host);
    const entry = owned(library, playlistId);
    entry.members.splice(memberIndex(entry, memberId), 1);
    await saveLibrary(host, library);
  },
  async movePlaylistMember(playlistId, memberId, anchorId, before, host) {
    const library = await readLibrary(host);
    const entry = owned(library, playlistId);
    if (memberId === anchorId) throw new PluginError("INVALID_ARGUMENT");
    entry.members.splice(memberIndex(entry, memberId), 1);
    entry.members.splice(memberIndex(entry, anchorId) + (before ? 0 : 1), 0, memberId);
    await saveLibrary(host, library);
  },
  async searchAlbums(query, page, pageSize) { return pageOf(album.title.toLowerCase().includes(query.toLowerCase()) ? [album] : [], page, pageSize); },
  async albumDetail(id, page, pageSize) { checkId(id, album.id); return { summary: album, tracks: pageOf([track], page, pageSize) }; },
  async searchArtists(query, page, pageSize) { return pageOf(artist.name.toLowerCase().includes(query.toLowerCase()) ? [artist] : [], page, pageSize); },
  async artistDetail(id, page, pageSize) { checkId(id, artist.id); return { summary: artist, tracks: pageOf([track], page, pageSize) }; },
  async rankings(page, pageSize) { return pageOf([ranking], page, pageSize); },
  async rankingTracks(id, page, pageSize) { checkId(id, ranking.id); return pageOf([track], page, pageSize); },
  async homeFeed(playlistLimit, newSongLimit, rankingLimit) {
    return { recommendedPlaylists: [playlist].slice(0, playlistLimit), newSongs: [track].slice(0, newSongLimit), rankings: [ranking].slice(0, rankingLimit) };
  },
  // This demo library is local to the plugin, so no platform account is necessary.
  async accountSummary() { return null; },
  async userPlaylists(page, pageSize, host) { return pageOf([playlist, ...(await readLibrary(host)).playlists.map(summary)], page, pageSize); },
  async savedAlbums(page, pageSize) { return pageOf([album], page, pageSize); },
  async followedArtists(page, pageSize) { return pageOf([artist], page, pageSize); },
  async isFavorite(requested, host) { return (await host.storage.get(`favorite.${requested.id}`)) === "true"; },
  async setFavorite(requested, favorite, host) {
    checkId(requested.id, track.id);
    await host.storage.set(`favorite.${requested.id}`, String(favorite));
  },
});
