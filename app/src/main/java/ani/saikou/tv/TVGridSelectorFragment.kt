package ani.saikou.tv

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.activityViewModels
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.ObjectAdapter
import androidx.leanback.widget.Presenter
import androidx.leanback.app.SearchSupportFragment
import androidx.lifecycle.lifecycleScope
import ani.saikou.*
import ani.saikou.media.Media
import ani.saikou.media.MediaDetailsViewModel
import ani.saikou.parsers.AnimeSources
import ani.saikou.parsers.HAnimeSources
import ani.saikou.parsers.ShowResponse
import ani.saikou.settings.UserInterfaceSettings
import ani.saikou.tv.components.SearchFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TVGridSelectorFragment : SearchFragment(), SearchSupportFragment.SearchResultProvider {

    var sourceId: Int = 0
    var mediaId: Int = 0

    val model: MediaDetailsViewModel by activityViewModels()
    var media: Media? = null

    private lateinit var adapter: ArrayObjectAdapter
    private val scope = requireActivity().lifecycleScope
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Similar titles"
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        progressBarManager.setRootView(this.view as ViewGroup)
        progressBarManager.initialDelay = 0
        progressBarManager.show()

        adapter = ArrayObjectAdapter(AnimeSourcePresenter(requireActivity()))
        super.setSearchResultProvider(this)

        observeData()
        super.onViewCreated(view, savedInstanceState)
    }

    fun observeData() {
        model.getMedia().observe(viewLifecycleOwner) {
            media = it
            if (media != null) {
                search(media!!.mangaName())
            }
        }
        model.responses.observe(viewLifecycleOwner) { list ->
            if (list != null) {
                if (list.isNotEmpty()) {
                    adapter.clear()
                    adapter.addAll(0, list)
                } else {
                    Toast.makeText(requireContext(), "Nothing found, try another source.", Toast.LENGTH_LONG).show()
                }
                progressBarManager.hide()
            }
        }
    }

    fun search(query: String?) {
        searchJob?.cancel()
        searchJob = scope.launch {
            delay(500)
            val currentMedia = media
            currentMedia ?: return@launch
            withContext(Dispatchers.Main) {
                progressBarManager.show()
            }
            model.responses.postValue(
                withContext(Dispatchers.IO) {
                    val source = (if (!currentMedia.isAdult) AnimeSources else HAnimeSources)[currentMedia.selected!!.source]
                    source.search(query)
                }
            )
        }
    }

    override fun getResultsAdapter(): ObjectAdapter {
        return adapter
    }

    override fun onQueryTextChange(newQuery: String?): Boolean {
        if (newQuery != null && newQuery.isNotEmpty()) {
            search(newQuery)
        }
        return true
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        if (query != null) {
            search(query)
        }
        return true
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Timer replaced with lifecycleScope delay — no cleanup needed
    }

    inner class AnimeSourcePresenter(private val activity: FragmentActivity) : Presenter() {

        private val uiSettings =
            loadData<UserInterfaceSettings>("ui_settings") ?: UserInterfaceSettings()

        override fun onCreateViewHolder(parent: ViewGroup?): ViewHolder {
            return AnimeSourceViewHolder(
                TvAnimeCardBinding.inflate(
                    LayoutInflater.from(parent?.context ?: activity), parent, false
                )
            )
        }

        override fun onBindViewHolder(viewHolder: ViewHolder?, item: Any?) {
            val itemView = (viewHolder as AnimeSourceViewHolder).view
            val b = (viewHolder as AnimeSourceViewHolder).binding
            setAnimation(activity, b.root, uiSettings)
            val showResponse = item as ShowResponse
            itemView.setSafeOnClickListener { clicked(showResponse) }

            b.itemCompactImage.loadImage(showResponse.coverUrl.url)
            b.itemCompactOngoing.visibility = View.GONE
            b.itemCompactTitle.text = showResponse.name
            b.itemCompactScore.visibility = View.GONE
            b.itemCompactScoreBG.visibility = View.GONE
            b.itemCompactUserProgress.visibility = View.GONE
            b.itemCompactTotal.visibility = View.GONE
        }

        fun clicked(source: ShowResponse) {
            requireActivity().lifecycleScope.launch(Dispatchers.IO) {
                model.overrideEpisodes(sourceId, source, mediaId)
            }
            requireActivity().supportFragmentManager.popBackStack()
        }

        override fun onUnbindViewHolder(viewHolder: ViewHolder?) {
        }

        inner class AnimeSourceViewHolder(val binding: TvAnimeCardBinding) : Presenter.ViewHolder(binding.root) {}
    }
}
