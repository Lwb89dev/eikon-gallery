package app.eikon.gallery.data.db

import androidx.room.withTransaction
import javax.inject.Inject

/** Runs a block of database work as one transaction. A seam so logic that needs one can be tested without Room. */
interface Transactor {
    suspend fun <T> run(block: suspend () -> T): T
}

class RoomTransactor @Inject constructor(private val database: EikonDatabase) : Transactor {
    override suspend fun <T> run(block: suspend () -> T): T = database.withTransaction { block() }
}
