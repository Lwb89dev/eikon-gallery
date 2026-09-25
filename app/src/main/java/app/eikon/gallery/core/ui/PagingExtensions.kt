package app.eikon.gallery.core.ui

import androidx.paging.compose.LazyPagingItems

/**
 * The item at [index] if it is loaded, without triggering a page load. Unlike `peek`, an index that
 * is out of range (the list shrank a moment ago) yields null instead of throwing.
 */
fun <T : Any> LazyPagingItems<T>.peekOrNull(index: Int): T? =
    if (index in 0 until itemCount) peek(index) else null

/** The item at [index], triggering its page to load, or null while it is still a placeholder. */
fun <T : Any> LazyPagingItems<T>.getOrNull(index: Int): T? =
    if (index in 0 until itemCount) get(index) else null
