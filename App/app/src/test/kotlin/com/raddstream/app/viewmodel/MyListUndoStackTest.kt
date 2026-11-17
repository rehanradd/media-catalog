package com.raddstream.app.viewmodel

import com.raddstream.app.data.local.FavoritePost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * My List review (Nov 2026): Undo is a stack, not a single slot. A
 * second remove while the first snackbar is still up must not eat the
 * first undo, and the row comes back verbatim — its `addedAt` included —
 * so it returns to the place it was taken from instead of jumping to
 * the top as "just added". The ViewModel wires this stack to the
 * snackbar (one snackbar per entry, newest first); the stack itself is
 * the pure part, and it is pinned here. A full ViewModel test is not
 * possible in this module today: the ViewModel's constructor takes the
 * concrete [com.raddstream.app.repository.FavoritesRepository] and
 * [com.raddstream.app.repository.CatalogRepository], neither is open,
 * and no mocking library or coroutines-test dependency is on the test
 * classpath — so the stack semantics, the part that can regress
 * silently, are tested directly instead of through the ViewModel.
 */
class MyListUndoStackTest {

    private fun removed(id: Int, title: String, addedAt: Long) = RemovedFavorite(
        row = FavoritePost(id = id, title = title, cleanTitle = title, addedAt = addedAt),
        title = title,
    )

    @Test
    fun `a second remove does not eat the first undo`() {
        val stack = MyListUndoStack()
        stack.push(removed(1, "A", addedAt = 100L))
        stack.push(removed(2, "B", addedAt = 200L))
        assertEquals("B", stack.topTitle)
        assertEquals(2, stack.size)

        // The newest snackbar resolves first — Undo or dismiss, either
        // way the older entry is still underneath it.
        assertEquals(2, stack.pop()?.row?.id)
        assertEquals("A", stack.topTitle)
        assertEquals(1, stack.size)
        assertEquals(1, stack.pop()?.row?.id)
        assertNull(stack.topTitle)
        assertEquals(0, stack.size)
    }

    @Test
    fun `the restored row keeps its original addedAt`() {
        val stack = MyListUndoStack()
        stack.push(removed(1, "Middle Title", addedAt = 100L))
        val entry = stack.pop()
        // The ViewModel restores this exact row: under RECENT sort the
        // title returns to its original position, not to the top.
        assertEquals(100L, entry?.row?.addedAt)
        assertEquals("Middle Title", entry?.title)
    }

    @Test
    fun `popping an empty stack is a no-op`() {
        val stack = MyListUndoStack()
        assertNull(stack.pop())
        assertNull(stack.topTitle)
        assertEquals(0, stack.size)
    }

    @Test
    fun `clear drops every queued undo`() {
        val stack = MyListUndoStack()
        stack.push(removed(1, "A", addedAt = 100L))
        stack.push(removed(2, "B", addedAt = 200L))
        stack.clear()
        assertNull(stack.pop())
        assertEquals(0, stack.size)
    }
}
