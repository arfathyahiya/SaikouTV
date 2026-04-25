# TVGridSelectorFragment Search Bar

## Goal
Add a user-input search bar to TV mode's "Wrong title" correction flow, matching mobile's SourceSearchDialogFragment functionality.

## Current State
- TVGridSelectorFragment extends VerticalGridSupportFragment
- Auto-searches using media.mangaName() — no user input
- Shows "Similar titles" grid, no search bar
- Toast "Nothing found, try another source." if empty

## Design
- TVGridSelectorFragment extends SearchFragment instead of VerticalGridSupportFragment
- Implements SearchSupportFragment.SearchResultProvider
- SearchFragment.onCreateView inflates tv_search_fragment.xml (already has SearchBar + VerticalGrid)
- Pre-fill search bar with media.mangaName()
- onQueryTextChange: debounced 500ms search with user's typed query
- onQueryTextSubmit: submit query
- Item click: use current search query instead of media name for overrideEpisodes

## Files Changed
- app/src/main/java/ani/saikou/tv/TVGridSelectorFragment.kt — rewrite to extend SearchFragment
- No layout changes needed
