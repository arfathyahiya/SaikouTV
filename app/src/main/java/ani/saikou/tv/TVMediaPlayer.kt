package ani.saikou.tv

import android.app.AlertDialog
import android.app.Dialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.session.MediaSessionCompat
import android.util.DisplayMetrics
import android.view.*
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.view.setPadding
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.leanback.app.VideoSupportFragment
import androidx.leanback.app.VideoSupportFragmentGlueHost
import androidx.leanback.media.PlaybackGlue
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.lifecycle.lifecycleScope
import androidx.media.session.MediaButtonReceiver
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import ani.saikou.*
import ani.saikou.anilist.Anilist
import ani.saikou.anime.Episode
import ani.saikou.anime.VideoCache
import ani.saikou.media.Media
import ani.saikou.media.MediaDetailsViewModel
import ani.saikou.parsers.Subtitle
import ani.saikou.parsers.SubtitleType
import ani.saikou.parsers.Video
import ani.saikou.parsers.VideoExtractor
import ani.saikou.parsers.VideoType
import ani.saikou.settings.PlayerSettings
import ani.saikou.settings.UserInterfaceSettings
import ani.saikou.tv.utils.VideoPlayerGlue
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.analytics.AnalyticsListener
import com.google.android.exoplayer2.ext.leanback.LeanbackPlayerAdapter
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import com.google.android.exoplayer2.ext.okhttp.OkHttpDataSource
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.text.Cue
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.trackselection.MappingTrackSelector
import com.google.android.exoplayer2.ui.TrackSelectionDialogBuilder
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.HttpDataSource
import com.google.android.exoplayer2.upstream.cache.CacheDataSource
import com.google.android.exoplayer2.util.MimeTypes
import com.google.android.exoplayer2.video.VideoSize
import com.google.firebase.crashlytics.ktx.crashlytics
import com.google.firebase.ktx.Firebase
import com.lagradost.nicehttp.ignoreAllSSLErrors
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import java.lang.Runnable
import java.net.URI
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

class TVMediaPlayer: VideoSupportFragment(), VideoPlayerGlue.OnActionClickedListener, Player.Listener {

    private val resumeWindow = "resumeWindow"
    private val resumePosition = "resumePosition"

    lateinit var media: Media

    private lateinit var exoPlayer: ExoPlayer
    private lateinit var cacheFactory : CacheDataSource.Factory
    private lateinit var playbackParameters: PlaybackParameters
    private lateinit var trackSelector: DefaultTrackSelector
    private lateinit var mediaItem : MediaItem
    private var interacted = false
    private var currentEpisodeIndex = 0
    private var isInitialized = false
    private lateinit var episodeArr: List<String>

    private var currentWindow = 0
    private var originalAspectRatio: Float? = null
    private var screenHeight: Int = 0
    private var screenWidth: Int = 0
    private var currentViewMode: Int = 0
    private var playbackPosition: Long = 0

    private lateinit var episode: Episode
    private lateinit var episodes: MutableMap<String,Episode>
    private lateinit var playerGlue : VideoPlayerGlue

    private var mediaSession: MediaSessionCompat? = null

    private var extractor: VideoExtractor? = null
    private var video: Video? = null
    private var subtitle: Subtitle? = null

    private val model: MediaDetailsViewModel by activityViewModels()
    private var episodeLength: Float = 0f

    private var settings = PlayerSettings()
    private var uiSettings = UserInterfaceSettings()

    private var linkSelector: TVSelectorFragment? = null
    private var tvSubtitleView: TextView? = null


    private fun setupTvSubtitleOverlay() {
        if (tvSubtitleView != null) return

        val root = view as? ViewGroup ?: return

        tvSubtitleView = TextView(requireContext()).apply {
            text = ""
            textSize = 26f
            setTextColor(Color.WHITE)
            setShadowLayer(4f, 2f, 2f, Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(32)
            visibility = View.GONE
        }

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = 90
        }

        root.addView(tvSubtitleView, params)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val metrics: WindowMetrics = requireContext().getSystemService(WindowManager::class.java).currentWindowMetrics
            screenHeight = metrics.bounds.height()
            screenWidth = metrics.bounds.width()
        } else {
            val outMetrics = DisplayMetrics()
            val display = requireActivity().windowManager.defaultDisplay
            display.getMetrics(outMetrics)
            screenHeight = outMetrics.heightPixels
            screenWidth = outMetrics.widthPixels
        }
        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        settings = loadData("player_settings") ?: PlayerSettings().apply { saveData("player_settings",this) }
        uiSettings = loadData("ui_settings") ?: UserInterfaceSettings().apply { saveData("ui_settings",this) }

        if (savedInstanceState != null) {
            currentWindow = savedInstanceState.getInt(resumeWindow)
            playbackPosition = savedInstanceState.getLong(resumePosition)
        }

        linkSelector = null

        val episodeObserverRunnable = Runnable {
            model.getEpisode().observe(viewLifecycleOwner) {
                if (it != null) {
                    episode = it
                    media.selected = model.loadSelected(media)
                    model.setMedia(media)

                    lifecycleScope.launch(Dispatchers.IO){
                        extractor?.onVideoStopped(video)
                    }

                    extractor = episode.extractors?.find { it.server.name == episode.selectedExtractor }
                    video = extractor?.videos?.getOrNull(episode.selectedVideo)
                    subtitle = extractor?.subtitles?.find {
                        it.language.equals("English", true) ||
                                it.language.equals("en-US", true) ||
                                it.language.equals("eng", true) ||
                                it.language.equals("en", true)
                    } ?: extractor?.subtitles?.firstOrNull()

                    lifecycleScope.launch(Dispatchers.IO){
                        extractor?.onVideoPlayed(video)
                    }
                    if(extractor == null) {
                        if (linkSelector == null) {
                            episode.extractors?.let { extractors ->
                                if(extractors.size > 0) {
                                    linkSelector = TVSelectorFragment.newInstance(media, true)
                                    linkSelector?.setStreamLinks(extractors)
                                    parentFragmentManager.beginTransaction().addToBackStack(null)
                                        .replace(R.id.main_detail_fragment, linkSelector!!)
                                        .commit()
                                }
                            }
                        }
                    } else {
                        if(!isInitialized) {
                            episodeArr = episodes.keys.toList()
                            currentEpisodeIndex = episodeArr.indexOf(media.anime!!.selectedEpisode!!)
                            playbackPosition = loadData("${media.id}_${it.number}", requireActivity()) ?: 0
                            if (playbackPosition != 0L) {
                                showContinuePlaying()
                            } else {
                                initVideo()
                            }
                        }
                    }
                }
            }
        }

        activity?.onBackPressedDispatcher?.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                savePlaybackProgress()
                if(isInitialized)
                    progress {
                        isEnabled = false
                        isInitialized = false
                        playbackPosition = exoPlayer.currentPosition
                        exoPlayer.stop()
                        exoPlayer.release()
                        VideoCache.release()
                        requireActivity().supportFragmentManager.popBackStack("detail", FragmentManager.POP_BACK_STACK_INCLUSIVE)
                    }
                else {
                    isEnabled = false
                    isInitialized = false
                    requireActivity().supportFragmentManager.popBackStack("detail", FragmentManager.POP_BACK_STACK_INCLUSIVE)
                }
            }
        })

        surfaceView.keepScreenOn = true

        episodeObserverRunnable.run()

        //Handle Media
        model.setMedia(media)
        episodes = media.anime!!.episodes!!

        //Set Episode, to invoke getEpisode() at Start
        model.setEpisode(episodes[media.anime!!.selectedEpisode!!]!!,"invoke")
        episodeArr = episodes.keys.toList()
        currentEpisodeIndex = episodeArr.indexOf(media.anime!!.selectedEpisode!!)
    }

    fun showContinuePlaying() {
            val time = String.format(
                "%02d:%02d:%02d", TimeUnit.MILLISECONDS.toHours(playbackPosition),
                TimeUnit.MILLISECONDS.toMinutes(playbackPosition) - TimeUnit.HOURS.toMinutes(
                    TimeUnit.MILLISECONDS.toHours(
                        playbackPosition
                    )
                ),
                TimeUnit.MILLISECONDS.toSeconds(playbackPosition) - TimeUnit.MINUTES.toSeconds(
                    TimeUnit.MILLISECONDS.toMinutes(
                        playbackPosition
                    )
                )
            )
            AlertDialog.Builder(requireContext(), R.style.TVDialogTheme).setTitle("Continue from ${time}?").apply {
                setCancelable(false)
                setPositiveButton("Yes") { d, _ ->
                    initVideo()
                    d.dismiss()
                }
                setNegativeButton("No") { d, _ ->
                    playbackPosition = 0L
                    initVideo()
                    d.dismiss()
                }
            }.show()
    }

    fun initVideo() {
        setupTvSubtitleOverlay()
        val simpleCache = VideoCache.getInstance(requireContext())

        originalAspectRatio = null
        val httpClient = OkHttpClient().newBuilder().ignoreAllSSLErrors().apply {
            followRedirects(true)
            followSslRedirects(true)
        }.build()
        val dataSourceFactory = DataSource.Factory {
            val dataSource: HttpDataSource = OkHttpDataSource.Factory(httpClient).createDataSource()
            defaultHeaders.forEach {
                dataSource.setRequestProperty(it.key, it.value)
            }

            subtitle?.url?.headers?.forEach {
                dataSource.setRequestProperty(it.key, it.value)
            }

            video?.url?.headers?.forEach {
                dataSource.setRequestProperty(it.key, it.value)
            }
            dataSource
        }
        cacheFactory = CacheDataSource.Factory().apply {
            setCache(simpleCache)
            setUpstreamDataSourceFactory(dataSourceFactory)
        }
        trackSelector = DefaultTrackSelector(requireContext())
        trackSelector.setParameters(
            trackSelector.buildUponParameters()
                .setPreferredTextLanguage("en")
                .setSelectUndeterminedTextLanguage(true)
                .setRendererDisabled(C.TRACK_TYPE_TEXT, false)
        )
        trackSelector.setParameters(
            trackSelector.buildUponParameters()
                .setMinVideoSize(
                    loadData("maxWidth",requireActivity()) ?:720, loadData("maxHeight",requireActivity())
                        ?:480)
                .setMaxVideoSize(1,1)
        )


        //Speed
        val speeds     =  if(settings.cursedSpeeds) arrayOf(1f , 1.25f , 1.5f , 1.75f , 2f , 2.5f , 3f , 4f, 5f , 10f , 25f, 50f) else arrayOf( 0.25f , 0.33f , 0.5f , 0.66f , 0.75f , 1f , 1.25f , 1.33f , 1.5f , 1.66f , 1.75f , 2f )
        val speedsName = speeds.map { "${it}x" }.toTypedArray()
        var curSpeed   = loadData("${media.id}_speed",requireActivity()) ?:settings.defaultSpeed

        playbackParameters = PlaybackParameters(speeds[curSpeed])

        val mimeType = when (video?.format) {
            VideoType.M3U8 -> MimeTypes.APPLICATION_M3U8
            VideoType.DASH -> MimeTypes.APPLICATION_MPD
            else -> MimeTypes.APPLICATION_MP4
        }

        val subConfig = subtitle?.let { selectedSub ->
            val subMimeType = when (selectedSub.type) {
                SubtitleType.VTT -> MimeTypes.TEXT_VTT
                SubtitleType.ASS -> MimeTypes.TEXT_SSA
                SubtitleType.SRT -> MimeTypes.APPLICATION_SUBRIP
                else -> when {
                    selectedSub.url.url.endsWith(".vtt", true) -> MimeTypes.TEXT_VTT
                    selectedSub.url.url.endsWith(".ass", true) -> MimeTypes.TEXT_SSA
                    selectedSub.url.url.endsWith(".srt", true) -> MimeTypes.APPLICATION_SUBRIP
                    else -> MimeTypes.TEXT_VTT
                }
            }

            MediaItem.SubtitleConfiguration.Builder(Uri.parse(selectedSub.url.url))
                .setMimeType(subMimeType)
                .setLanguage(selectedSub.language)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
        }

        val builder = MediaItem.Builder()
            .setUri(video!!.url.url)
            .setMimeType(mimeType)

        if (subConfig != null) {
            builder.setSubtitleConfigurations(listOf(subConfig))
        }

        mediaItem = builder.build()

        exoPlayer = ExoPlayer.Builder(requireContext())
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheFactory))
            .setTrackSelector(trackSelector)
            .build().apply {
                playWhenReady = true
                this.playbackParameters = this@TVMediaPlayer.playbackParameters
                setMediaItem(mediaItem)
                prepare()
                loadData<Long>("${media.id}_${media.anime!!.selectedEpisode}_max")?.apply {
                    if (this <= playbackPosition) playbackPosition = max(0, this - 5)
                }
                seekTo(playbackPosition)
            }

        isInitialized = true
        exoPlayer.addListener(this)
        exoPlayer.addListener(object : Player.Listener {
            override fun onCues(cues: MutableList<Cue>) {
                val text = cues.joinToString("\n") { it.text?.toString().orEmpty() }.trim()

                tvSubtitleView?.text = text
                tvSubtitleView?.visibility = if (text.isNotEmpty()) View.VISIBLE else View.GONE
            }
        })
        val mediaButtonReceiver = ComponentName(requireActivity(), MediaButtonReceiver::class.java)
        MediaSessionCompat(requireContext(), "Player", mediaButtonReceiver, null).let { media ->
            mediaSession = media
            val mediaSessionConnector = MediaSessionConnector(media)
            mediaSessionConnector.setPlayer(exoPlayer)
            mediaSessionConnector.setClearMediaItemsOnStop(true)
            media.isActive = true
        }

        exoPlayer.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoSizeChanged(eventTime: AnalyticsListener.EventTime, videoSize: VideoSize) {
                if (originalAspectRatio == null) {
                    originalAspectRatio = videoSize.width.toFloat() / videoSize.height.toFloat()
                    playerGlue.shouldShowResizeAction = (screenHeight / screenWidth.toFloat()) != originalAspectRatio
                }
            }
        })

        playerGlue = VideoPlayerGlue(requireActivity(), LeanbackPlayerAdapter(requireActivity(), exoPlayer, 16), true,this)
        playerGlue.host = VideoSupportFragmentGlueHost(this)
        playerGlue.title = media.mainName()

        if(!episode.title.isNullOrEmpty())
            playerGlue.subtitle = getString(R.string.ep)+ " "+ episode.number + ": "+ episode.title
        else
            playerGlue.subtitle = getString(R.string.ep)+ " "+ episode.number


        playerGlue.playWhenPrepared()
        playerGlue.host.setOnKeyInterceptListener { view, keyCode, event ->
            if (playerGlue.host.isControlsOverlayVisible) return@setOnKeyInterceptListener false

            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && event.action == KeyEvent.ACTION_DOWN) {
                playerGlue.fastForward()
                preventControlsOverlay(playerGlue)
                return@setOnKeyInterceptListener true
            }

            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN) {
                playerGlue.rewind()
                preventControlsOverlay(playerGlue)
                return@setOnKeyInterceptListener true
            }

            false
        }

        playerGlue.addPlayerCallback(object : PlaybackGlue.PlayerCallback() {
            override fun onPlayCompleted(glue: PlaybackGlue?) {
                super.onPlayCompleted(glue)
                onNext()
            }
        })

        adapter = ArrayObjectAdapter(playerGlue.playbackRowPresenter).apply {
            add(playerGlue.controlsRow)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (isInitialized) {
            outState.putInt(resumeWindow, exoPlayer.currentMediaItemIndex)
            outState.putLong(resumePosition, exoPlayer.currentPosition)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onTracksChanged(tracks: Tracks) {
        super.onTracksChanged(tracks)
        playerGlue.shouldShowQualityAction = tracks.groups.size > 2
    }

    // QUALITY SELECTOR
    private fun initPopupQuality(): Dialog {
        val trackSelectionDialogBuilder =
            TrackSelectionDialogBuilder(requireContext(), "Available Qualities", exoPlayer, C.TRACK_TYPE_VIDEO)
        trackSelectionDialogBuilder.setTheme(R.style.QualitySelectorDialogTheme)
        trackSelectionDialogBuilder.setTrackNameProvider {
            if (it.frameRate > 0f) it.height.toString() + "p" else it.height.toString() + "p (fps : N/A)"
        }
        trackSelectionDialogBuilder.setAllowAdaptiveSelections(false)
        return trackSelectionDialogBuilder.build()
    }

    private fun preventControlsOverlay(playerGlue: VideoPlayerGlue) = view?.postDelayed({
        playerGlue.host.showControlsOverlay(false)
        playerGlue.host.hideControlsOverlay(false)
    }, 10)

    override fun onQuality() {
        if(playerGlue.shouldShowQualityAction)
            initPopupQuality().show()
    }

    override fun onPrevious() {
        if(currentEpisodeIndex>0) {
            change(currentEpisodeIndex - 1)
        }
    }

    override fun onStop() {
        super.onStop()
        mediaSession?.release()
    }

    override fun onNext() {
        if(isInitialized) {
            nextEpisode{ i-> progress { change(currentEpisodeIndex + i) } }
        }
    }

    override fun onPause() {
        super.onPause()
        savePlaybackProgress()
    }

    override fun onPlayerPause() {
        savePlaybackProgress()
    }

    fun savePlaybackProgress() {
        if(isInitialized) {
            media.anime?.let { anime ->
                saveData(
                    "${media.id}_${anime.selectedEpisode}",
                    exoPlayer.currentPosition,
                    requireActivity()
                )
                updateWatchNextChannel()
            }
        }
    }

    fun updateWatchNextChannel() {
        val sharedPref = requireActivity().getPreferences(Context.MODE_PRIVATE) ?: return

        val intent = Intent(requireContext(), TVMainActivity::class.java)
        intent.putExtra("media", media.id)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)

        val builder = WatchNextProgram.Builder()
        builder.setType(TvContractCompat.WatchNextPrograms.TYPE_TV_SERIES)
            .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
            .setLastEngagementTimeUtcMillis(Date().time)
            .setTitle(media.mainName())
            .setEpisodeNumber(episode.number.toIntOrNull() ?: 0)
            .setEpisodeTitle(episode.title)
            .setDescription(episode.desc)
            .setIntent(intent)

        episode.thumb?.let {
            builder.setPosterArtUri(Uri.parse(it.url))
        }

        media.cover?.let {
            builder.setThumbnailUri(Uri.parse(it))
        }

        try {
            val watchNextID = sharedPref.getString(TVMainActivity.watchNextChannelIDKey, null)
            watchNextID?.let {
                requireContext().contentResolver.update(
                    Uri.parse(it),
                    builder.build().toContentValues(),
                    null,
                    null
                )
            } ?: run {
                val watchNextProgramUri = requireContext().contentResolver
                    .insert(
                        TvContractCompat.WatchNextPrograms.CONTENT_URI,
                        builder.build().toContentValues()
                    )

                with(sharedPref.edit()) {
                    putString(TVMainActivity.watchNextChannelIDKey, watchNextProgramUri.toString())
                    apply()
                }
            }
        }catch (e: Exception) {
            Firebase.crashlytics.log("Error updating watchnext "+e.message)
        }
    }

    override fun onResize() {
        if(!playerGlue.shouldShowResizeAction) return

        currentViewMode++
        if (currentViewMode>2) currentViewMode = 0

        var params = surfaceView.layoutParams
        when(currentViewMode){
            0 -> {  //Original
                params.height = screenHeight
                params.width = (screenHeight*originalAspectRatio!!).toInt()
            }
            1 -> {  //FitWidth
                params.height = (screenWidth/originalAspectRatio!!).toInt()
                params.width = screenWidth
            }
            2 -> {  //FitXY
                params.height = screenHeight
                params.width = screenWidth
            }
        }
        surfaceView.layoutParams = params
    }

    private fun nextEpisode(runnable: ((Int)-> Unit) ){
        var isFiller = true
        var i=1
        while (isFiller) {
            if (episodeArr.size > currentEpisodeIndex + i) {
                isFiller = if (settings.autoSkipFiller) episodes[episodeArr[currentEpisodeIndex + i]]?.filler?:false else false
                if (!isFiller) runnable.invoke(i)
                i++
            }
            else {
                isFiller = false
            }
        }
    }

    fun change(index:Int){
        if(isInitialized) {
            exoPlayer.stop()
            exoPlayer.release()
            VideoCache.release()

            isInitialized = false
            saveData(
                "${media.id}_${episodeArr[currentEpisodeIndex]}",
                exoPlayer.currentPosition,
                requireActivity()
            )
            val selected = media.selected
            episodeLength= 0f
            media.anime!!.selectedEpisode = episodeArr[index]
            model.setMedia(media)
            model.epChanged.postValue(false)
            selected?.let {
                lifecycleScope.launch(Dispatchers.IO) {
                    if (media.selected!!.server != null)
                        model.loadEpisodeSingleVideo(
                            episodes[media.anime!!.selectedEpisode!!]!!,
                            it,
                            true
                        )
                    else
                        model.loadEpisodeVideos(
                            episodes[media.anime!!.selectedEpisode!!]!!,
                            it.source,
                            true
                        )
                }
            }

            //model.setEpisode(episodes[media.anime!!.selectedEpisode!!]!!,"change")

            /*media.anime?.episodes?.get(media.anime!!.selectedEpisode!!)?.let{ ep ->
                media.selected = model.loadSelected(media)
                model.setMedia(media)
            }*/
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == ExoPlayer.STATE_READY) {
            exoPlayer.play()
            if (episodeLength == 0f) {
                episodeLength = exoPlayer.duration.toFloat()
            }
        }
        if(playbackState == Player.STATE_ENDED && settings.autoPlay){
            if(interacted)
                onNext()
        }
        super.onPlaybackStateChanged(playbackState)
    }

    private fun progress(runnable: Runnable){
        if (exoPlayer.currentPosition / episodeLength > settings.watchPercentage && Anilist.userid != null) {
            if(loadData<Boolean>("${media.id}_save_progress")!=false && if (media.isAdult) settings.updateForH else true)
                updateAnilist(media, media.anime!!.selectedEpisode!!)
            runnable.run()
        } else {
            runnable.run()
        }
    }

    private fun updateAnilist(media: Media, number: String){
        if(Anilist.userid!=null) {
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                val a = number.toFloatOrNull()?.roundToInt()
                if (a != media.userProgress) {
                    Anilist.mutation.editList(
                        media.id,
                        a,
                        status = if (media.userStatus == "REPEATING") media.userStatus else "CURRENT"
                    )
                }
                media.userProgress = a
                Refresh.all()
            }
        }
    }
}