package io.quarkiverse.jimmer.runtime.repo

import org.babyfish.jimmer.Slice
import java.util.UUID

class UuidV7Slice<T>(
	rows: List<T>,
	val nextCursor: UUID?,
) : Slice<T>(rows, true, nextCursor == null) {
	val hasNext: Boolean
		get() = nextCursor != null
}

data class UuidV7Page<T>(
    val rows: List<T>,
    val nextCursor: UUID?,
    val totalRowCount: Long
) {
    val hasNext: Boolean
        get() = nextCursor != null
}
