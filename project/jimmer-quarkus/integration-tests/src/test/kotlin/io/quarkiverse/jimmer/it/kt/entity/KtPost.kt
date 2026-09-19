package io.quarkiverse.jimmer.it.kt.entity

import org.babyfish.jimmer.sql.Entity
import org.babyfish.jimmer.sql.Id
import org.babyfish.jimmer.sql.JoinColumn
import org.babyfish.jimmer.sql.ManyToOne
import org.babyfish.jimmer.sql.Table
import java.util.UUID

@Entity
@Table(name = "kt_post")
interface KtPost {

	@Id
	val id: UUID

	val title: String

	@ManyToOne
	@JoinColumn(name = "author_id")
	val author: KtAuthor?
}
