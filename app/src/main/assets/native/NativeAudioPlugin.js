// Fork: music plays in the app's own player (media3), not in the web view: the web client hands over the
// whole queue (getPlaylist makes it do so) and only browses. The app plays, keeps the queue, shows the mini
// player and the notification, and reports to the server; this plugin mirrors its state for the web client.
export class NativeAudioPlugin {
    constructor({ playbackManager, loading }) {
        window['NativeAudioPlugin'] = this;

        this.playbackManager = playbackManager;
        this.loading = loading;

        this.name = 'Music player';
        this.type = 'mediaplayer';
        this.id = 'nativeaudio';
        this.priority = -2;
        this.isLocalPlayer = true;

        this._native = window['NativeAudio'];
        this._currentTime = 0;
        this._duration = null;
        this._paused = true;
        this._playlist = [];
        this._currentId = null;
        this._repeatMode = 'RepeatNone';
        this._shuffleMode = 'Sorted';

        // The web player bar is not needed while the app's player plays
        const style = document.createElement('style');
        style.textContent = 'body.nativeAudioActive .nowPlayingBar { display: none !important; }';
        document.head.appendChild(style);
    }

    canPlayMediaType(mediaType) {
        return mediaType === 'Audio';
    }

    canQueueMediaType(mediaType) {
        return this.canPlayMediaType(mediaType);
    }

    canPlayItem(item) {
        return this._native.isEnabled() && !this.playbackManager.syncPlayEnabled;
    }

    static toTracks(items) {
        return (items || []).filter(item => item && item.Id).map(item => {
            const track = Object.assign({}, item);
            delete track.PresetMediaSource;
            delete track.MediaSources;
            return track;
        });
    }

    async play(options) {
        const items = NativeAudioPlugin.toTracks(options.items);
        this._playlist = items.map(item => Object.assign({ PlaylistItemId: item.Id }, item));
        this._paused = false;
        document.body.classList.add('nativeAudioActive');
        this._native.play(JSON.stringify({
            items,
            startIndex: options.startIndex || 0,
            startPositionTicks: options.startPositionTicks || 0
        }));
        this.loading.hide();
    }

    queue(options) {
        this._native.queue(JSON.stringify({ items: NativeAudioPlugin.toTracks(options.items), next: false }));
    }

    queueNext(options) {
        this._native.queue(JSON.stringify({ items: NativeAudioPlugin.toTracks(options.items), next: true }));
    }

    async shuffle(item) {
        const apiClient = window.ApiClient;
        const result = await apiClient.getItems(apiClient.getCurrentUserId(), {
            ParentId: item.Id,
            Recursive: true,
            IncludeItemTypes: 'Audio',
            SortBy: 'Random',
            Limit: 300,
            Fields: 'MediaSources'
        });
        await this.play({ items: result.Items, startIndex: 0 });
    }

    async instantMix(item) {
        const apiClient = window.ApiClient;
        const result = await apiClient.getInstantMixFromItem(item.Id, {
            UserId: apiClient.getCurrentUserId(),
            Limit: 200,
            Fields: 'MediaSources'
        });
        await this.play({ items: result.Items, startIndex: 0 });
    }

    async stop(destroyPlayer) {
        this._native.stop();
        this._paused = true;
        document.body.classList.remove('nativeAudioActive');
    }

    destroy() {}

    nextTrack() {
        this._native.next();
    }

    previousTrack() {
        this._native.previous();
    }

    seek(ticks) {
        this._native.seekMs(Math.floor(ticks / 10000));
    }

    currentTime(ms) {
        if (ms !== undefined) {
            this._native.seekMs(Math.floor(ms));
        }
        return this._currentTime;
    }

    duration() {
        return this._duration;
    }

    volume() {
        return 100;
    }

    getVolume() {
        return 100;
    }

    setVolume() {}

    volumeUp() {}

    volumeDown() {}

    isMuted() {
        return false;
    }

    setMute() {}

    toggleMute() {}

    paused() {
        return this._paused;
    }

    pause() {
        this._native.pause();
    }

    unpause() {
        this._native.resume();
    }

    playPause() {
        if (this._paused) {
            this.unpause();
        } else {
            this.pause();
        }
    }

    getRepeatMode() {
        return this._repeatMode;
    }

    setRepeatMode(mode) {
        this._repeatMode = mode;
        this._native.setRepeatMode(mode);
    }

    getQueueShuffleMode() {
        return this._shuffleMode;
    }

    setQueueShuffleMode(mode) {
        this._shuffleMode = mode;
        this._native.setShuffle(mode === 'Shuffle');
    }

    toggleQueueShuffleMode() {
        this.setQueueShuffleMode(this._shuffleMode === 'Shuffle' ? 'Sorted' : 'Shuffle');
    }

    canSetAudioStreamIndex() {
        return false;
    }

    setAudioStreamIndex() {}

    setSubtitleStreamIndex() {}

    async changeAudioStream() {}

    async changeSubtitleStream() {}

    getPlaylist() {
        return Promise.resolve(this._playlist);
    }

    getPlaylistSync() {
        return this._playlist;
    }

    getCurrentPlaylistIndex() {
        return this._playlist.findIndex(item => item.PlaylistItemId === this._currentId);
    }

    nextItem() {
        const index = this.getCurrentPlaylistIndex();
        const item = index >= 0 ? this._playlist[index + 1] : null;
        return item ? { item, index: index + 1 } : null;
    }

    clearQueue(clearCurrentItem) {
        if (clearCurrentItem) this.stop();
        return Promise.resolve();
    }

    movePlaylistItem() {
        return Promise.resolve();
    }

    isPlaying(mediaType) {
        return !this._paused && (!mediaType || mediaType === 'Audio');
    }

    seekRelative(ticks) {
        this._native.seekMs(Math.max(0, Math.floor(this._currentTime + ticks / 10000)));
    }

    fastForward(ms) {
        this._native.seekMs(Math.floor(this._currentTime + ms));
    }

    rewind(ms) {
        this._native.seekMs(Math.max(0, Math.floor(this._currentTime - ms)));
    }

    getCurrentPlaylistItemId() {
        return this._currentId;
    }

    setCurrentPlaylistItem(playlistItemId) {
        const index = this._playlist.findIndex(item => item.PlaylistItemId === playlistItemId);
        if (index >= 0) this._native.playIndex(index);
        return Promise.resolve();
    }

    removeFromPlaylist() {
        return Promise.resolve();
    }

    async getDeviceProfile() {
        return {
            Name: 'Music player',
            MaxStreamingBitrate: 140000000,
            MaxStaticBitrate: 140000000,
            MusicStreamingTranscodingBitrate: 320000,
            DirectPlayProfiles: [{ Type: 'Audio' }],
            CodecProfiles: [],
            SubtitleProfiles: [],
            TranscodingProfiles: [{ Type: 'Audio', Container: 'mp3', AudioCodec: 'mp3', Protocol: 'http', Context: 'Streaming' }]
        };
    }

    // From the app: what plays now, about once a second (also when it was started from the downloads)
    onNativeState(state) {
        this._paused = !state.playing;
        this._currentTime = state.positionMs || 0;
        this._duration = state.durationMs || null;
        this._currentId = state.itemId || null;
        if (state.active) {
            document.body.classList.add('nativeAudioActive');
        } else {
            document.body.classList.remove('nativeAudioActive');
        }
    }

    // From the app: the app's player starts, a video or audio playing in the web view stops
    stopWebPlayback() {
        const player = this.playbackManager.getCurrentPlayer && this.playbackManager.getCurrentPlayer();
        if (player && player !== this && player.isLocalPlayer && player.id !== 'exoplayer') {
            this.playbackManager.stop(player);
        }
    }
}
